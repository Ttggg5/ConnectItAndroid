package com.connectit.android.screen

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import com.connectit.android.connection.ConnectionEngine
import com.connectit.android.net.MAX_FRAMES_IN_FLIGHT
import com.connectit.android.net.ScreenMessage
import com.connectit.android.net.ScreenRejectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 等待系統「開始錄製或投放」確認畫面的原因:自己要主動分享,或是同意對方的觀看請求。 */
enum class ProjectionPurpose { OFFER, ACCEPT_REQUEST }

sealed interface ScreenMirrorState {
    val sessionId: String?

    data object Idle : ScreenMirrorState {
        override val sessionId: String? = null
    }

    /** 我請對方分享他的螢幕,等對方同意。 */
    data class OutgoingRequest(override val sessionId: String) : ScreenMirrorState

    /** 我要分享我的螢幕給對方(已經取得擷取權限),等對方接受。 */
    data class OutgoingOffer(override val sessionId: String) : ScreenMirrorState

    /** 對方想看我的螢幕,等我同意(畫面上顯示確認對話框)。 */
    data class IncomingRequest(override val sessionId: String) : ScreenMirrorState

    /** 對方想分享他的螢幕給我看,等我接受。 */
    data class IncomingOffer(override val sessionId: String) : ScreenMirrorState

    /** 需要畫面去跳出系統的螢幕擷取確認(見 ScreenMirrorHost),拿到結果後呼叫 [ScreenMirrorController.onProjectionResult]。 */
    data class AwaitingProjection(
        override val sessionId: String,
        val purpose: ProjectionPurpose,
        val allowControl: Boolean,
        /** 使用者想同時分享聲音:畫面要先要 RECORD_AUDIO 權限,再跳擷取確認。 */
        val shareAudio: Boolean = false,
    ) : ScreenMirrorState

    /**
     * 正在分享我的螢幕。[allowControl] 是使用者的選擇,[controlActive] 是實際上對方能不能操控
     * (還要無障礙服務有開才行,見 [ScreenControlAccessibilityService])。
     */
    data class Sharing(
        override val sessionId: String,
        val allowControl: Boolean,
        val controlActive: Boolean,
        val width: Int,
        val height: Int,
        /** 是否真的在分享聲音(使用者有勾選、有權限、而且成功開始擷取)。 */
        val shareAudio: Boolean = false,
    ) : ScreenMirrorState

    /** 正在看對方的螢幕;[width] 為 0 代表對方還沒送出畫面資訊(剛接受分享)。 */
    data class Viewing(
        override val sessionId: String,
        val width: Int = 0,
        val height: Int = 0,
        val platform: String? = null,
        val allowControl: Boolean = false,
        val fps: Int = 0,
        /** 分享端回報的游標位置(0~1,目前只有 Windows 分享端會送),觸控板模式用來讓圓圈對準真正的游標。 */
        val cursorX: Double? = null,
        val cursorY: Double? = null,
        /** 對方有分享聲音時的取樣率,沒有聲音時為 null。 */
        val audioSampleRate: Int? = null,
    ) : ScreenMirrorState
}

/**
 * 螢幕鏡像的狀態機(分享端與觀看端都在這裡),跑在已配對的 [ConnectionEngine] 連線上,協定見 [ScreenMessage]。
 *
 * 所有狀態轉換都排到 [confined](同一時間只跑一個工作的 dispatcher)上執行:UI 操作、讀取迴圈送來的
 * 訊息、系統擷取權限的結果分別來自不同執行緒,這樣不用到處加鎖也不會有競爭。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenMirrorController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: ConnectionEngine,
    /** 給使用者看的訊息(Snackbar/近期紀錄)。 */
    private val onNotice: (String) -> Unit,
    /** 不再需要 MediaProjection 時呼叫,讓服務把前景服務類型改回只有 dataSync。 */
    private val onProjectionReleased: () -> Unit,
) {
    private val confined = Dispatchers.Default.limitedParallelism(1)

    private val _state = MutableStateFlow<ScreenMirrorState>(ScreenMirrorState.Idle)
    val state: StateFlow<ScreenMirrorState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<ScreenFrame?>(null)
    /** 觀看中最新的一張畫面。 */
    val frame: StateFlow<ScreenFrame?> = _frame.asStateFlow()

    // ---- 分享端 ----
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var senderJob: Job? = null
    private var controlWatcherJob: Job? = null
    private val injector = ScreenInputInjector()
    @Volatile private var lastSentSeq = 0
    @Volatile private var lastAckedSeq = 0
    private val ackSignal = Channel<Unit>(Channel.CONFLATED)

    // ---- 觀看端 ----
    private var decodedFrames = 0
    private var fpsWindowStartedAt = 0L

    /**
     * 要解碼的畫面。由 [DECODE_WORKERS] 個工作同時解碼(一張 1080p JPEG 在手機上要 10~25ms,
     * 單執行緒解碼撐不到 60fps),每個工作有自己的 [ScreenFrameDecoder](點陣圖池不能共用)。
     * 解完可能不照順序,只顯示比目前畫面新的那張。
     */
    private val decodeQueue = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
    private val shownSeq = AtomicInteger(0)

    /** 我方要送出的 ack 與操作事件:用單一工作依序送出,點擊的按下/放開才不會因為並行送出而前後顛倒。 */
    private val outgoing = Channel<ScreenMessage>(Channel.UNLIMITED)

    /** 要送出的聲音片段,由單一工作依序送出;網路塞住時只保留最新的一小段,舊的丟掉(聲音晚到不如不要)。 */
    private val outgoingAudio = Channel<ByteArray>(capacity = 25, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // 注意:這個 init 區塊啟動的工作會用到上面宣告的 channel,Kotlin 依宣告順序初始化,
    // 它們一定要宣告在 init 之前,否則背景工作會讀到還是 null 的欄位(啟動時就閃退)。
    init {
        scope.launch(Dispatchers.IO) {
            for (message in outgoing) engine.sendScreenMessage(message)
        }
        scope.launch(Dispatchers.IO) {
            for (pcm in outgoingAudio) {
                if (_state.value is ScreenMirrorState.Sharing) engine.sendScreenAudio(pcm, pcm.size)
            }
        }
        repeat(DECODE_WORKERS) {
            scope.launch(Dispatchers.Default) {
                val decoder = ScreenFrameDecoder()
                var decoderSession: String? = null
                for ((sessionId, payload) in decodeQueue) {
                    if (decoderSession != sessionId) {
                        decoder.clear()
                        decoderSession = sessionId
                    }
                    decodeAndShow(decoder, sessionId, payload)
                }
            }
        }
    }

    private var timeoutJob: Job? = null

    private fun launchConfined(block: suspend () -> Unit) {
        scope.launch(confined) { block() }
    }

    // ===================== UI 操作 =====================

    /** 請對方分享他的螢幕給我看。 */
    fun requestViewPeer() = launchConfined {
        if (_state.value != ScreenMirrorState.Idle || !engine.isConnected) return@launchConfined
        val id = newSessionId()
        _state.value = ScreenMirrorState.OutgoingRequest(id)
        send(ScreenMessage(ScreenMessage.REQUEST, sessionId = id))
        startTimeout(id, REQUEST_TIMEOUT_MS, "對方沒有回應。對方可能還沒按下同意,或使用的是不支援螢幕鏡像的舊版 ConnectIt。")
    }

    /** 我要分享我的螢幕:先跳出系統的擷取確認,拿到權限後才送出邀請。 */
    fun startShareOffer(allowControl: Boolean, shareAudio: Boolean = false) = launchConfined {
        if (_state.value != ScreenMirrorState.Idle || !engine.isConnected) return@launchConfined
        _state.value = ScreenMirrorState.AwaitingProjection(newSessionId(), ProjectionPurpose.OFFER, allowControl, shareAudio)
    }

    fun respondToIncomingRequest(accept: Boolean, allowControl: Boolean, shareAudio: Boolean = false) = launchConfined {
        val current = _state.value as? ScreenMirrorState.IncomingRequest ?: return@launchConfined
        if (!accept) {
            send(ScreenMessage(ScreenMessage.REJECT, sessionId = current.sessionId, reason = ScreenRejectReason.DECLINED))
            _state.value = ScreenMirrorState.Idle
            return@launchConfined
        }
        _state.value = ScreenMirrorState.AwaitingProjection(current.sessionId, ProjectionPurpose.ACCEPT_REQUEST, allowControl, shareAudio)
    }

    fun respondToIncomingOffer(accept: Boolean) = launchConfined {
        val current = _state.value as? ScreenMirrorState.IncomingOffer ?: return@launchConfined
        if (!accept) {
            send(ScreenMessage(ScreenMessage.REJECT, sessionId = current.sessionId, reason = ScreenRejectReason.DECLINED))
            _state.value = ScreenMirrorState.Idle
            return@launchConfined
        }
        startViewing(current.sessionId)
        send(ScreenMessage(ScreenMessage.ACCEPT, sessionId = current.sessionId))
    }

    /**
     * 系統擷取確認畫面的結果。[projection] 為 null 代表使用者取消了、或這台裝置不支援螢幕擷取
     * ([unsupported] 為 true)。
     */
    fun onProjectionResult(projection: MediaProjection?, unsupported: Boolean = false) = launchConfined {
        val current = _state.value as? ScreenMirrorState.AwaitingProjection
        if (current == null) {
            // 等待確認期間對方已經取消/斷線了,拿到的權杖直接丟掉。
            projection?.let { runCatching { it.stop() } }
            if (projection != null) onProjectionReleased()
            return@launchConfined
        }

        if (projection == null) {
            if (current.purpose == ProjectionPurpose.ACCEPT_REQUEST) {
                send(
                    ScreenMessage(
                        ScreenMessage.REJECT, sessionId = current.sessionId,
                        reason = if (unsupported) ScreenRejectReason.UNSUPPORTED else ScreenRejectReason.PERMISSION_DENIED,
                    )
                )
            }
            _state.value = ScreenMirrorState.Idle
            onNotice(if (unsupported) "這台裝置不支援螢幕擷取。" else "已取消螢幕分享。")
            return@launchConfined
        }

        this.projection = projection
        when (current.purpose) {
            ProjectionPurpose.ACCEPT_REQUEST -> startSharing(current.sessionId, current.allowControl, current.shareAudio)
            ProjectionPurpose.OFFER -> {
                pendingOfferAllowControl = current.allowControl
                pendingOfferShareAudio = current.shareAudio
                _state.value = ScreenMirrorState.OutgoingOffer(current.sessionId)
                send(ScreenMessage(ScreenMessage.OFFER, sessionId = current.sessionId))
                startTimeout(current.sessionId, OFFER_TIMEOUT_MS, "對方沒有回應。對方可能還沒按下接受,或使用的是不支援螢幕鏡像的舊版 ConnectIt。")
            }
        }
    }

    private var pendingOfferAllowControl = false
    private var pendingOfferShareAudio = false

    // ---- 聲音 ----
    private var audioCapturer: ScreenAudioCapturer? = null
    /** 讀取迴圈([handleAudio])也會讀,所以要 @Volatile。 */
    @Volatile private var audioPlayer: ScreenAudioPlayer? = null

    /** 觀看中是否把對方的聲音靜音(只影響本機播放)。 */
    @Volatile var audioMuted = false
        set(value) {
            field = value
            audioPlayer?.muted = value
        }


    /** 分享中切換是否同時分享聲音(要先有 RECORD_AUDIO 權限)。 */
    fun setShareAudio(share: Boolean) = launchConfined {
        val current = _state.value as? ScreenMirrorState.Sharing ?: return@launchConfined
        if (current.shareAudio == share) return@launchConfined
        val started = if (share) startAudioCapture() else {
            stopAudioCapture()
            false
        }
        if (share && !started) onNotice("無法擷取聲音(需要 Android 10 以上,並允許錄音權限)。")
        updateSharing(current.copy(shareAudio = started))
    }

    /** 分享端:用同一個 MediaProjection 開始擷取其他 App 播放的聲音,成功回傳 true。 */
    private fun startAudioCapture(): Boolean {
        val projection = projection ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !ScreenAudioFormat.hasRecordPermission(context)) return false
        stopAudioCapture()
        return try {
            val capturer = ScreenAudioCapturer(projection) { buffer, length -> outgoingAudio.trySend(buffer.copyOf(length)) }
            capturer.start()
            audioCapturer = capturer
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun stopAudioCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) audioCapturer?.stop()
        audioCapturer = null
    }

    /** 觀看端:依對方 screen-info 裡的聲音格式建立/重建/關閉播放器。 */
    private fun updateAudioPlayer(sampleRate: Int?, channels: Int?) {
        val viewing = _state.value is ScreenMirrorState.Viewing
        val player = audioPlayer
        val wantedChannels = channels ?: ScreenAudioFormat.CHANNELS
        if (viewing && sampleRate != null && sampleRate > 0 && player != null && player.sampleRate == sampleRate && player.channels == wantedChannels) return
        player?.release()
        audioPlayer = null
        if (viewing && sampleRate != null && sampleRate > 0) {
            audioPlayer = runCatching { ScreenAudioPlayer(sampleRate, wantedChannels).also { it.muted = audioMuted } }.getOrNull()
        }
    }

    /** 收到一段聲音(讀取迴圈上呼叫),直接排進播放器的佇列。 */
    fun handleAudio(pcm: ByteArray) {
        if (_state.value !is ScreenMirrorState.Viewing) return
        audioPlayer?.enqueue(pcm)
    }

    /** 取消還在等對方回應的請求/邀請。 */
    fun cancelOutgoing() = launchConfined {
        when (val current = _state.value) {
            is ScreenMirrorState.OutgoingRequest, is ScreenMirrorState.OutgoingOffer -> {
                send(ScreenMessage(ScreenMessage.STOP, sessionId = current.sessionId))
                cleanup()
            }
            is ScreenMirrorState.AwaitingProjection -> cleanup()
            else -> Unit
        }
    }

    /** 分享中切換是否允許對方操控。 */
    fun setAllowControl(allow: Boolean) = launchConfined {
        val current = _state.value as? ScreenMirrorState.Sharing ?: return@launchConfined
        if (current.allowControl == allow) return@launchConfined
        if (!allow) injector.reset()
        updateSharing(current.copy(allowControl = allow))
    }

    /** 停止分享/停止觀看。 */
    fun stop() = launchConfined {
        val current = _state.value
        if (current is ScreenMirrorState.Sharing || current is ScreenMirrorState.Viewing) {
            send(ScreenMessage(ScreenMessage.STOP, sessionId = current.sessionId))
            cleanup()
        }
    }

    /** 觀看端:把使用者的觸控/滑鼠/鍵盤操作送給分享端(分享端沒有允許操控時不送)。 */
    fun sendInput(message: ScreenMessage) {
        val current = _state.value as? ScreenMirrorState.Viewing ?: return
        if (!current.allowControl) return
        outgoing.trySend(message.copy(type = ScreenMessage.INPUT, sessionId = current.sessionId))
    }

    /** 連線已經中斷(不論哪一方造成的):不能再送任何訊息,直接清理。 */
    fun onConnectionClosed() = launchConfined {
        if (_state.value != ScreenMirrorState.Idle) {
            cleanup()
        }
    }

    // ===================== 收到對方的訊息 =====================

    /** 在連線的讀取迴圈上呼叫,這裡只負責把工作排進 [confined]。 */
    fun handleMessage(message: ScreenMessage) {
        if (message.type == ScreenMessage.ACK) {
            // 確認訊息很頻繁(每張畫面一則),而且只改兩個欄位,直接處理不用排隊。
            val seq = message.seq ?: return
            if ((_state.value as? ScreenMirrorState.Sharing)?.sessionId != message.sessionId) return
            if (seq > lastAckedSeq) lastAckedSeq = seq
            ackSignal.trySend(Unit)
            return
        }
        if (message.type == ScreenMessage.INPUT) {
            launchConfined {
                val current = _state.value as? ScreenMirrorState.Sharing ?: return@launchConfined
                if (current.sessionId == message.sessionId && current.controlActive) injector.handle(message)
            }
            return
        }
        launchConfined { handleControl(message) }
    }

    private suspend fun handleControl(message: ScreenMessage) {
        val id = message.sessionId ?: return
        val current = _state.value
        when (message.type) {
            ScreenMessage.REQUEST, ScreenMessage.OFFER -> {
                if (current != ScreenMirrorState.Idle) {
                    send(ScreenMessage(ScreenMessage.REJECT, sessionId = id, reason = ScreenRejectReason.BUSY))
                    return
                }
                _state.value = if (message.type == ScreenMessage.REQUEST) {
                    ScreenMirrorState.IncomingRequest(id)
                } else {
                    ScreenMirrorState.IncomingOffer(id)
                }
            }
            ScreenMessage.ACCEPT -> {
                if (current is ScreenMirrorState.OutgoingOffer && current.sessionId == id) {
                    startSharing(id, pendingOfferAllowControl, pendingOfferShareAudio)
                }
            }
            ScreenMessage.REJECT -> {
                if ((current is ScreenMirrorState.OutgoingRequest || current is ScreenMirrorState.OutgoingOffer) && current.sessionId == id) {
                    cleanup()
                    onNotice(describeReject(message.reason))
                }
            }
            ScreenMessage.INFO -> {
                when {
                    current is ScreenMirrorState.OutgoingRequest && current.sessionId == id -> {
                        startViewing(id)
                        applyInfo(message)
                        updateAudioPlayer(message.audioSampleRate, message.audioChannels)
                    }
                    current is ScreenMirrorState.Viewing && current.sessionId == id -> {
                        applyInfo(message)
                        updateAudioPlayer(message.audioSampleRate, message.audioChannels)
                    }
                }
            }
            ScreenMessage.STOP -> {
                if (current.sessionId != id) return
                val wasActive = current is ScreenMirrorState.Sharing || current is ScreenMirrorState.Viewing
                cleanup()
                onNotice(if (wasActive) "對方已結束螢幕鏡像。" else "對方已取消螢幕鏡像的請求。")
            }
        }
    }

    /** 收到一張畫面(讀取迴圈上呼叫)。解碼排到 [confined],解完才回 ack,分享端就不會送得比我們解得快。 */
    fun handleFrame(payload: ByteArray) {
        val current = _state.value as? ScreenMirrorState.Viewing ?: return
        if (payload.size <= 4) return
        decodeQueue.trySend(current.sessionId to payload)
    }

    private fun decodeAndShow(decoder: ScreenFrameDecoder, sessionId: String, payload: ByteArray) {
        val seq = ScreenFrameDecoder.readSeq(payload)
        val decoded = if ((_state.value as? ScreenMirrorState.Viewing)?.sessionId == sessionId) decoder.decode(payload) else null
        outgoing.trySend(ScreenMessage(ScreenMessage.ACK, sessionId = sessionId, seq = seq))
        if (decoded == null || (_state.value as? ScreenMirrorState.Viewing)?.sessionId != sessionId) return
        // 兩個工作同時解碼,晚到的舊畫面直接丟掉,不要讓畫面倒退。
        while (true) {
            val shown = shownSeq.get()
            if (seq <= shown) return
            if (shownSeq.compareAndSet(shown, seq)) break
        }
        _frame.value = decoded
        launchConfined { countFrame() }
    }

    // ===================== 內部 =====================

    private fun startViewing(id: String) {
        cancelTimeout()
        shownSeq.set(0)
        _frame.value = null
        decodedFrames = 0
        fpsWindowStartedAt = SystemClock.elapsedRealtime()
        _state.value = ScreenMirrorState.Viewing(id)
    }

    private fun applyInfo(message: ScreenMessage) {
        val current = _state.value as? ScreenMirrorState.Viewing ?: return
        _state.value = current.copy(
            width = message.width ?: current.width,
            height = message.height ?: current.height,
            platform = message.platform ?: current.platform,
            allowControl = message.allowControl ?: current.allowControl,
            cursorX = message.x ?: current.cursorX,
            cursorY = message.y ?: current.cursorY,
            // 不能沿用舊值:分享端關掉聲音時就是不帶這個欄位。
            audioSampleRate = message.audioSampleRate,
        )
    }

    private fun countFrame() {
        decodedFrames++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - fpsWindowStartedAt
        if (elapsed >= 1000) {
            val fps = (decodedFrames * 1000 / elapsed).toInt()
            decodedFrames = 0
            fpsWindowStartedAt = now
            (_state.value as? ScreenMirrorState.Viewing)?.let { if (it.fps != fps) _state.value = it.copy(fps = fps) }
        }
    }

    private suspend fun startSharing(id: String, allowControl: Boolean, shareAudio: Boolean) {
        cancelTimeout()
        val projection = projection
        if (projection == null) {
            send(ScreenMessage(ScreenMessage.STOP, sessionId = id))
            cleanup()
            return
        }

        val capturer = ScreenCapturer(
            context, projection,
            onSizeChanged = { _, _ -> launchConfined { (_state.value as? ScreenMirrorState.Sharing)?.let { updateSharing(it.refreshSize()) } } },
            onStoppedBySystem = {
                launchConfined {
                    val current = _state.value
                    if (current is ScreenMirrorState.Sharing || current is ScreenMirrorState.OutgoingOffer) {
                        send(ScreenMessage(ScreenMessage.STOP, sessionId = current.sessionId))
                        cleanup()
                        onNotice("螢幕擷取已被系統停止。")
                    }
                }
            },
        )
        try {
            capturer.start()
        } catch (e: Exception) {
            runCatching { capturer.stop() }
            send(ScreenMessage(ScreenMessage.STOP, sessionId = id))
            cleanup()
            onNotice("無法開始擷取螢幕:${e.message}")
            return
        }
        this.capturer = capturer
        lastSentSeq = 0
        lastAckedSeq = 0

        val sharing = ScreenMirrorState.Sharing(
            sessionId = id,
            allowControl = allowControl,
            controlActive = allowControl && ScreenControlAccessibilityService.running.value,
            width = capturer.width,
            height = capturer.height,
            shareAudio = shareAudio && startAudioCapture(),
        )
        if (shareAudio && !sharing.shareAudio) onNotice("無法擷取聲音(需要 Android 10 以上,並允許錄音權限),只分享畫面。")
        _state.value = sharing
        sendInfo(sharing)

        senderJob = scope.launch(Dispatchers.IO) { runSender(id, capturer) }
        // 使用者分享到一半才去開無障礙服務(或把它關掉)時,要重新告訴觀看端能不能操控。
        controlWatcherJob = scope.launch(confined) {
            ScreenControlAccessibilityService.running.drop(1).collect { running ->
                val current = _state.value as? ScreenMirrorState.Sharing ?: return@collect
                updateSharing(current.copy(controlActive = current.allowControl && running))
            }
        }
    }

    private fun ScreenMirrorState.Sharing.refreshSize(): ScreenMirrorState.Sharing {
        val capturer = capturer ?: return this
        return copy(width = capturer.width, height = capturer.height)
    }

    private suspend fun updateSharing(updated: ScreenMirrorState.Sharing) {
        val effective = updated.copy(controlActive = updated.allowControl && ScreenControlAccessibilityService.running.value)
        val previous = _state.value
        _state.value = effective
        if (previous != effective) sendInfo(effective)
    }

    private suspend fun sendInfo(sharing: ScreenMirrorState.Sharing) {
        send(
            ScreenMessage(
                ScreenMessage.INFO, sessionId = sharing.sessionId,
                width = sharing.width, height = sharing.height,
                platform = "android", allowControl = sharing.controlActive,
                audioSampleRate = if (sharing.shareAudio) ScreenAudioFormat.SAMPLE_RATE else null,
                audioChannels = if (sharing.shareAudio) ScreenAudioFormat.CHANNELS else null,
            )
        )
    }

    /** 傳送迴圈:有新畫面、而且還沒確認的畫面少於 [MAX_FRAMES_IN_FLIGHT] 張時,才取最新的一張壓縮送出。 */
    private suspend fun runSender(id: String, capturer: ScreenCapturer) {
        try {
            while (scope.isActive && (_state.value as? ScreenMirrorState.Sharing)?.sessionId == id) {
                capturer.awaitFrame()
                while (lastSentSeq - lastAckedSeq >= MAX_FRAMES_IN_FLIGHT) {
                    // 正常情況下 ack 很快就會回來;等太久(對方卡住)就當作已確認,避免整個畫面停住。
                    if (withTimeoutOrNull(ACK_TIMEOUT_MS) { ackSignal.receive() } == null) lastAckedSeq = lastSentSeq
                }
                val startedAt = SystemClock.elapsedRealtime()
                val (buffer, length) = capturer.encodeLatest(ScreenCapturer.DEFAULT_QUALITY) ?: continue
                val seq = lastSentSeq + 1
                ScreenFrameDecoder.writeSeq(buffer, seq)
                engine.sendScreenFrame(buffer, length)
                lastSentSeq = seq
                // 限制最高約 60fps:畫面一直在動(例如播影片)時不要把 CPU/網路吃滿。
                val spent = SystemClock.elapsedRealtime() - startedAt
                if (spent < MIN_FRAME_INTERVAL_MS) delay(MIN_FRAME_INTERVAL_MS - spent)
            }
        } catch (e: Exception) {
            // 擷取器被停止(awaitFrame 的 channel 被關閉)或協程被取消,結束迴圈即可。
        }
    }

    private suspend fun cleanup() {
        cancelTimeout()
        senderJob?.cancel()
        senderJob = null
        controlWatcherJob?.cancel()
        controlWatcherJob = null
        injector.reset()
        // 聲音擷取共用同一個 MediaProjection,要在 projection 停掉之前先停。
        stopAudioCapture()
        audioPlayer?.release()
        audioPlayer = null

        val hadProjection = projection != null
        capturer?.stop()
        capturer = null
        if (projection != null) {
            runCatching { projection?.stop() }
            projection = null
        }
        if (hadProjection) onProjectionReleased()

        shownSeq.set(0)
        _frame.value = null
        _state.value = ScreenMirrorState.Idle
    }

    private fun startTimeout(id: String, timeoutMs: Long, message: String) {
        cancelTimeout()
        timeoutJob = scope.launch(confined) {
            delay(timeoutMs)
            val current = _state.value
            if ((current is ScreenMirrorState.OutgoingRequest || current is ScreenMirrorState.OutgoingOffer) && current.sessionId == id) {
                send(ScreenMessage(ScreenMessage.STOP, sessionId = id))
                cleanup()
                onNotice(message)
            }
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private suspend fun send(message: ScreenMessage) {
        engine.sendScreenMessage(message)
    }

    private fun describeReject(reason: String?): String = when (reason) {
        ScreenRejectReason.BUSY -> "對方正在進行另一個螢幕鏡像,請稍後再試。"
        ScreenRejectReason.UNSUPPORTED -> "對方的裝置不支援螢幕擷取。"
        ScreenRejectReason.PERMISSION_DENIED -> "對方沒有允許擷取螢幕。"
        else -> "對方拒絕了螢幕鏡像。"
    }

    private fun newSessionId() = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val OFFER_TIMEOUT_MS = 60_000L
        const val ACK_TIMEOUT_MS = 3_000L
        /** 最高約 60fps。 */
        const val MIN_FRAME_INTERVAL_MS = 16L
        const val DECODE_WORKERS = 2
    }
}
