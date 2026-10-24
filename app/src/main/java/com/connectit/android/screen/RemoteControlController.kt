package com.connectit.android.screen

import android.content.Context
import com.connectit.android.connection.PeerConnection
import com.connectit.android.net.ScreenInputAction
import com.connectit.android.net.ScreenMessage
import com.connectit.android.net.ScreenRejectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class RemoteControlPhase {
    IDLE,

    /** 我送出遙控請求,等對方同意。 */
    OUTGOING_REQUEST,

    /** 我正在遙控對方。 */
    CONTROLLING,

    /** 對方想遙控我,等我決定。 */
    INCOMING_REQUEST,

    /** 對方正在遙控我。 */
    CONTROLLED,
}

/** [platform]:遙控中對方的平台("windows"/"android"),控制端依此決定顯示哪些按鈕。 */
data class RemoteControlState(
    val phase: RemoteControlPhase,
    val sessionId: String? = null,
    val peer: PeerConnection? = null,
    val platform: String? = null,
) {
    companion object {
        val IDLE = RemoteControlState(RemoteControlPhase.IDLE)
    }
}

/**
 * 遙控器(對應 Windows 端 RemoteControlController.cs):不用開螢幕鏡像,只把按鍵(媒體鍵、音量、簡報翻頁、
 * 快捷鍵)送到另一台裝置,協定見 [ScreenMessage.REMOTE_REQUEST]。對方要先同意([isTrusted] 的裝置直接同意);
 * 同一時間只有一個遙控工作階段,跟螢幕鏡像互相獨立。Android 當受控端時只支援媒體鍵與音量
 * ([MediaKeyDispatcher],Android 不允許 App 注入任意按鍵)。
 */
class RemoteControlController(
    context: Context,
    private val scope: CoroutineScope,
    /** 給使用者看的訊息(Snackbar/近期紀錄)。 */
    private val onNotice: (String) -> Unit,
    /** 對方是不是信任的裝置(只認憑證指紋),是的話遙控請求直接同意。 */
    private val isTrusted: (PeerConnection) -> Boolean,
) {
    private val mediaKeys = MediaKeyDispatcher(context)
    private val lock = Any()
    private val _state = MutableStateFlow(RemoteControlState.IDLE)
    val state: StateFlow<RemoteControlState> = _state.asStateFlow()
    private var timeoutJob: Job? = null
    private val outgoing = Channel<Pair<PeerConnection, ScreenMessage>>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for ((peer, message) in outgoing) runCatching { peer.sendScreenMessage(message) }
        }
    }

    // ===================== 控制端 =====================

    fun requestControl(peer: PeerConnection) {
        val id = synchronized(lock) {
            if (_state.value.phase != RemoteControlPhase.IDLE || peer.isClosed) return
            UUID.randomUUID().toString().replace("-", "").also {
                _state.value = RemoteControlState(RemoteControlPhase.OUTGOING_REQUEST, it, peer)
                startTimeout(it)
            }
        }
        send(peer, ScreenMessage(ScreenMessage.REMOTE_REQUEST, sessionId = id))
    }

    /** 遙控中送出組合鍵:依序按下,再反過來依序放開(單一按鍵也走這裡)。 */
    fun sendShortcut(keys: List<String>) {
        val current = _state.value
        val peer = current.peer ?: return
        val id = current.sessionId ?: return
        if (current.phase != RemoteControlPhase.CONTROLLING || keys.isEmpty()) return
        keys.forEach { send(peer, ScreenMessage(ScreenMessage.INPUT, sessionId = id, action = ScreenInputAction.KEY_DOWN, key = it)) }
        keys.asReversed().forEach { send(peer, ScreenMessage(ScreenMessage.INPUT, sessionId = id, action = ScreenInputAction.KEY_UP, key = it)) }
    }

    // ===================== 受控端 =====================

    fun respondToIncomingRequest(accept: Boolean) {
        val current = synchronized(lock) {
            val current = _state.value
            if (current.phase != RemoteControlPhase.INCOMING_REQUEST) return
            _state.value = if (accept) current.copy(phase = RemoteControlPhase.CONTROLLED) else RemoteControlState.IDLE
            current
        }
        send(
            current.peer!!,
            if (accept) {
                ScreenMessage(ScreenMessage.REMOTE_ACCEPT, sessionId = current.sessionId, platform = "android")
            } else {
                ScreenMessage(ScreenMessage.REMOTE_REJECT, sessionId = current.sessionId, reason = ScreenRejectReason.DECLINED)
            },
        )
    }

    // ===================== 兩端共用 =====================

    /** 結束遙控(不論是控制端還是受控端),或取消/婉拒還在等回應的請求。 */
    fun stop() {
        val current = synchronized(lock) {
            val current = _state.value
            if (current.phase == RemoteControlPhase.IDLE) return
            reset()
            current
        }
        val peer = current.peer ?: return
        send(
            peer,
            if (current.phase == RemoteControlPhase.INCOMING_REQUEST) {
                ScreenMessage(ScreenMessage.REMOTE_REJECT, sessionId = current.sessionId, reason = ScreenRejectReason.DECLINED)
            } else {
                ScreenMessage(ScreenMessage.REMOTE_STOP, sessionId = current.sessionId)
            },
        )
    }

    fun onConnectionClosed(peer: PeerConnection) = synchronized(lock) {
        if (_state.value.phase != RemoteControlPhase.IDLE && _state.value.peer == peer) reset()
    }

    /**
     * 在連線的讀取迴圈上呼叫。遙控器的訊息(remote-*,以及送給遙控工作階段的按鍵)在這裡處理並回傳 true;
     * 其他訊息回傳 false,交給螢幕鏡像處理。
     */
    fun handleMessage(sender: PeerConnection, message: ScreenMessage): Boolean {
        val id = message.sessionId ?: return false
        var notice: String? = null
        var autoAccept = false
        synchronized(lock) {
            val current = _state.value
            when (message.type) {
                ScreenMessage.REMOTE_REQUEST -> {
                    if (current.phase != RemoteControlPhase.IDLE) {
                        send(sender, ScreenMessage(ScreenMessage.REMOTE_REJECT, sessionId = id, reason = ScreenRejectReason.BUSY))
                        return true
                    }
                    _state.value = RemoteControlState(RemoteControlPhase.INCOMING_REQUEST, id, sender)
                    autoAccept = isTrusted(sender)
                }
                ScreenMessage.REMOTE_ACCEPT -> if (current.phase == RemoteControlPhase.OUTGOING_REQUEST && current.sessionId == id && current.peer == sender) {
                    timeoutJob?.cancel()
                    _state.value = current.copy(phase = RemoteControlPhase.CONTROLLING, platform = message.platform)
                }
                ScreenMessage.REMOTE_REJECT -> if (current.phase == RemoteControlPhase.OUTGOING_REQUEST && current.sessionId == id && current.peer == sender) {
                    reset()
                    notice = if (message.reason == ScreenRejectReason.BUSY) "對方正在被其他裝置遙控,請稍後再試。" else "對方拒絕了遙控請求。"
                }
                ScreenMessage.REMOTE_STOP -> if (current.sessionId == id && current.peer == sender) {
                    val wasActive = current.phase == RemoteControlPhase.CONTROLLING || current.phase == RemoteControlPhase.CONTROLLED
                    reset()
                    notice = if (wasActive) "遙控已結束。" else "對方取消了遙控請求。"
                }
                ScreenMessage.INPUT -> {
                    if (current.phase != RemoteControlPhase.CONTROLLED || current.sessionId != id || current.peer != sender) return false
                    // 按下時才送一次(媒體鍵/音量是「按一下」的動作,放開不用再送)。
                    if (message.action == ScreenInputAction.KEY_DOWN) mediaKeys.dispatch(message.key)
                    return true
                }
                else -> return false
            }
        }
        if (autoAccept) {
            onNotice("已自動同意信任裝置「${sender.name}」的遙控請求。")
            respondToIncomingRequest(true)
        }
        notice?.let(onNotice)
        return true
    }

    private fun startTimeout(id: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(REQUEST_TIMEOUT_MS)
            val peer = synchronized(lock) {
                val current = _state.value
                if (current.phase != RemoteControlPhase.OUTGOING_REQUEST || current.sessionId != id) return@launch
                reset()
                current.peer
            }
            peer?.let { send(it, ScreenMessage(ScreenMessage.REMOTE_STOP, sessionId = id)) }
            onNotice("對方沒有回應遙控請求。對方可能還沒按下同意,或使用的是不支援遙控器的舊版 ConnectIt。")
        }
    }

    private fun reset() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = RemoteControlState.IDLE
    }

    /** 依序送出(組合鍵的按下/放開順序不能亂),由 init 裡的單一工作消化。 */
    private fun send(peer: PeerConnection, message: ScreenMessage) {
        outgoing.trySend(peer to message)
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 60_000L
    }
}
