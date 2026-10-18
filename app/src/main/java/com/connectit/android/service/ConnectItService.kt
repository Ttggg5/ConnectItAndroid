package com.connectit.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.connectit.android.MainActivity
import com.connectit.android.R
import com.connectit.android.connection.ConnectionEngine
import com.connectit.android.discovery.NsdDiscoveryManager
import com.connectit.android.model.ConnectedPeer
import com.connectit.android.model.ConnectionRequest
import com.connectit.android.model.ConnectionRequestOutcome
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.FileTransferEnded
import com.connectit.android.model.FolderEntry
import com.connectit.android.model.FolderTransferEnded
import com.connectit.android.model.TransferDirection
import com.connectit.android.model.TransferEndReason
import com.connectit.android.model.TransferRecord
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.repo.SettingsRepository
import com.connectit.android.util.DownloadStorage
import com.connectit.android.util.NetworkUtils
import com.connectit.android.util.SafUtils
import com.connectit.android.video.HostManifestEntry
import com.connectit.android.video.PlaybackControlState
import com.connectit.android.video.VideoHostPages
import com.connectit.android.video.VideoHostServer
import com.connectit.android.video.VideoServerPlaybackOptions
import com.connectit.android.video.VideoSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.random.Random

sealed interface ConnectionUiState {
    data object Idle : ConnectionUiState
    data class Connected(val peer: ConnectedPeer) : ConnectionUiState
}

/** 這台裝置目前是否正把自己的影片庫分享出去(見 [VideoHostServer])。 */
sealed interface VideoHostUiState {
    data object Idle : VideoHostUiState
    /** [url] 給其他裝置用瀏覽器觀看的網址(區網 IP + 連接埠),找不到區網 IP 時為 null。 */
    data class Running(val port: Int, val videoCount: Int, val folderName: String, val url: String? = null) : VideoHostUiState
}

/** 主動連線失敗(被拒絕/沒回應/連不到),供畫面顯示對應原因與建議的說明對話框。 */
data class ConnectFailure(val deviceName: String, val outcome: ConnectionRequestOutcome)

/**
 * 主機端遙控面板的播放清單設定:決定影片庫的顯示順序與上一部/下一部/自動播放下一部要切到哪一部。
 * 對應 Windows 端遙控面板的排序下拉選單/隨機播放/自動播放下一部。[shuffleSeed] 讓同一輪隨機播放
 * 期間順序固定(每次重算結果都一樣),只有重新開啟隨機播放才換一組。
 */
data class RemotePlaylistSettings(
    val sort: VideoSort = VideoSort.NAME_ASC,
    val shuffle: Boolean = VideoServerPlaybackOptions.DEFAULT_SHUFFLE,
    val shuffleSeed: Int = 0,
    val autoplayNext: Boolean = VideoServerPlaybackOptions.DEFAULT_AUTOPLAY_NEXT,
)

/** 遙控模式下目前這部播完、正在倒數準備切到下一部時的狀態,供遙控畫面顯示倒數與「取消」。 */
data class RemoteAutoplayCountdown(val nextRelativePath: String, val nextName: String, val secondsLeft: Int)

data class TransferUiState(
    val title: String,
    val statusText: String,
    val progress: Float,
)

/**
 * 背景前景服務,對應 Windows 端「關閉視窗會隱藏到系統匣但持續在背景運作」的概念:
 * 持有裝置配對用的 mDNS 廣播/搜尋、TCP 監聽與連線交握/檔案傳輸(見 [ConnectionEngine]),
 * 以及影片伺服器的 mDNS 廣播/搜尋——這台裝置既可以搜尋、觀看其他裝置分享的影片庫,
 * 也可以把自己的影片庫分享出去給其他裝置觀看(見 [VideoHostServer])。
 *
 * UI(MainActivity/ViewModel)透過 [LocalBinder] 綁定,用這裡暴露的 StateFlow 觀察狀態、
 * 呼叫對應方法送出使用者的操作。
 */
class ConnectItService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): ConnectItService = this@ConnectItService
    }

    private val binder = LocalBinder()

    lateinit var settingsRepository: SettingsRepository
        private set

    private lateinit var connectionEngine: ConnectionEngine
    private lateinit var deviceDiscovery: NsdDiscoveryManager
    private lateinit var videoDiscovery: NsdDiscoveryManager
    private lateinit var videoHostServer: VideoHostServer
    private lateinit var playbackControlState: PlaybackControlState

    /** 目前生效的設定快照,由 [onCreate] 裡的收集器持續更新,供不是 Compose 環境的內部邏輯
     * (通知開關、信任裝置比對、下載資料夾)同步讀取,不用每次都掛一個新的 Flow 收集器。 */
    @Volatile private var cachedSettings: AppSettings = AppSettings(deviceName = "", themeMode = AppThemeMode.AUTO)

    /** 顯示給使用者看的接收檔案位置(見 [com.connectit.android.util.DownloadStorage])。 */
    val downloadDisplayPath: String
        get() = cachedSettings.customDownloadFolderUri?.let { uriString ->
            runCatching { SafUtils.displayNameOf(applicationContext, Uri.parse(uriString)) }.getOrNull()
        } ?: DownloadStorage.DISPLAY_ROOT

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _videoServers = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val videoServers: StateFlow<List<DiscoveredDevice>> = _videoServers.asStateFlow()

    private val _videoHostState = MutableStateFlow<VideoHostUiState>(VideoHostUiState.Idle)
    val videoHostState: StateFlow<VideoHostUiState> = _videoHostState.asStateFlow()

    /** 遠端控制模式的即時播放狀態,供主機端遙控畫面(見 VideoHostControlScreen)顯示與驅動。
     * 觀眾端不用這個 StateFlow——它們是另一台裝置,只能透過 `/control/state` 輪詢取得。 */
    private val _playbackState = MutableStateFlow(
        PlaybackControlState.Snapshot(
            enabled = false, videoRelativePath = null, isPlaying = false, positionMs = 0,
            playbackRate = 1.0, volume = 1.0, muted = false, version = 0, updatedAtUtcMs = 0,
        ),
    )
    val playbackState: StateFlow<PlaybackControlState.Snapshot> = _playbackState.asStateFlow()

    private val _remotePlaylist = MutableStateFlow(RemotePlaylistSettings())
    val remotePlaylist: StateFlow<RemotePlaylistSettings> = _remotePlaylist.asStateFlow()

    private val _remoteAutoplayCountdown = MutableStateFlow<RemoteAutoplayCountdown?>(null)
    val remoteAutoplayCountdown: StateFlow<RemoteAutoplayCountdown?> = _remoteAutoplayCountdown.asStateFlow()

    // 自動播放下一部:主機端不一定真的在播放影片,所以「播完了」是由這個迴圈定期檢查碼表外插的位置
    // 有沒有走到片尾(見 checkRemoteAutoplay)。放在服務裡而不是遙控畫面,離開遙控畫面也照樣會接下一部。
    private var remoteAutoplayWatcher: Job? = null
    private var remoteAutoplayCountdownSeconds = VideoServerPlaybackOptions.DEFAULT_AUTOPLAY_COUNTDOWN_SECONDS
    private var remoteAutoplayDeadline = 0L

    private val _connectionState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)
    val connectionState: StateFlow<ConnectionUiState> = _connectionState.asStateFlow()

    private val _pendingConnectionRequest = MutableStateFlow<ConnectionRequest?>(null)
    val pendingConnectionRequest: StateFlow<ConnectionRequest?> = _pendingConnectionRequest.asStateFlow()

    private val _transferState = MutableStateFlow<TransferUiState?>(null)
    val transferState: StateFlow<TransferUiState?> = _transferState.asStateFlow()

    /** 正在等待對方接受連線請求的裝置(顯示「等待對方接受」對話框用),沒有在等時為 null。 */
    private val _connectingTo = MutableStateFlow<DiscoveredDevice?>(null)
    val connectingTo: StateFlow<DiscoveredDevice?> = _connectingTo.asStateFlow()
    private var connectJob: Job? = null

    private val _connectFailure = MutableStateFlow<ConnectFailure?>(null)
    val connectFailure: StateFlow<ConnectFailure?> = _connectFailure.asStateFlow()

    /** 這次連線期間的傳輸紀錄(新的在前),斷線或重新連線時清空。 */
    private val _transferHistory = MutableStateFlow<List<TransferRecord>>(emptyList())
    val transferHistory: StateFlow<List<TransferRecord>> = _transferHistory.asStateFlow()

    /** 剛接收完成、可以直接開啟的項目:傳輸卡片會切成「完成」狀態顯示「開啟」按鈕,直到使用者關閉或開始下一個傳輸。 */
    private val _completedTransfer = MutableStateFlow<TransferRecord?>(null)
    val completedTransfer: StateFlow<TransferRecord?> = _completedTransfer.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** 使用者從通知按下「關閉」時觸發一次,見 [MainActivity] 對這個流的收集:負責把畫面也收掉。 */
    private val _exitRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitRequested: SharedFlow<Unit> = _exitRequested.asSharedFlow()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // 使用者從通知直接按下「關閉」:除了把服務從前景/通知移除掉並結束它之外,還要通知
            // MainActivity(如果當下開著)把畫面也收掉——否則 Service 只要還被 Activity 綁定著
            // 就不會真的被系統回收(見下面 stopSelf() 的說明),使用者會覺得「按了關閉但 App 還開著」。
            _exitRequested.tryEmit(Unit)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            // MainViewModel.bind() 每次 App 被開啟(或從背景回到前景)都會呼叫 startForegroundService(),
            // 讓這裡重新收到一次 onStartCommand——不管 Service 本來是不是已經在跑。藉這個時機重貼一次
            // 常駐通知:如果使用者之前手動把通知清掉(部分廠牌系統允許清除 ongoing 通知),重新開啟 App
            // 時就能讓通知補回來,而不是要等 Service 被整個重建(onCreate 只會在真正重建時執行一次)。
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        settingsRepository = SettingsRepository(applicationContext)

        val nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        deviceDiscovery = NsdDiscoveryManager(nsdManager, NsdDiscoveryManager.DEVICE_SERVICE_TYPE)
        videoDiscovery = NsdDiscoveryManager(nsdManager, NsdDiscoveryManager.VIDEO_SERVICE_TYPE)
        playbackControlState = PlaybackControlState()
        videoHostServer = VideoHostServer(applicationContext, playbackControlState)
        connectionEngine = ConnectionEngine(applicationContext, lifecycleScope)
        connectionEngine.customDownloadFolderProvider = { cachedSettings.customDownloadFolderUri?.let(Uri::parse) }

        wireCallbacks()

        deviceDiscovery.startDiscovery()
        videoDiscovery.startDiscovery()

        // 持續把最新設定快取起來,供上面兩個非 Compose 的用途(通知開關、信任裝置比對)同步讀取,
        // 並即時套用連線逾時設定的變更。
        lifecycleScope.launch {
            settingsRepository.settings.collect { settings ->
                cachedSettings = settings
                connectionEngine.connectTimeoutMs = settings.connectTimeoutSeconds * 1000
            }
        }

        lifecycleScope.launch {
            val preferredPort = settingsRepository.settings.first().preferredPort
            val port = connectionEngine.startListening(preferredPort)
            settingsRepository.settings.map { it.deviceName }.distinctUntilChanged().collect { name ->
                deviceDiscovery.advertise(name, port)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        deviceDiscovery.teardown()
        videoDiscovery.teardown()
        videoHostServer.stop()
        connectionEngine.dispose()
        super.onDestroy()
    }

    // ===================== 對外操作 API =====================

    fun refreshDevices() = deviceDiscovery.refresh()

    /**
     * 送出連線請求並等待對方接受。等待期間 [connectingTo] 會是這台裝置(畫面顯示可以取消的等待對話框);
     * 被拒絕/沒回應/連不到時透過 [connectFailure] 告知原因。[onAccepted] 在對方接受後呼叫一次——
     * 從其他 App「分享」檔案過來時,用它在連上之後自動開始傳送。
     */
    fun connectTo(device: DiscoveredDevice, myDeviceName: String, onAccepted: (() -> Unit)? = null) {
        if (connectJob?.isActive == true) return
        _connectFailure.value = null
        _connectingTo.value = device
        connectJob = lifecycleScope.launch {
            try {
                val outcome = connectionEngine.requestConnection(device.host, device.port, myDeviceName, device.displayName)
                if (outcome == ConnectionRequestOutcome.ACCEPTED) {
                    onAccepted?.invoke()
                } else {
                    _connectFailure.value = ConnectFailure(device.displayName, outcome)
                    pushLog(
                        when (outcome) {
                            ConnectionRequestOutcome.REJECTED -> "${device.displayName} 拒絕了連線請求。"
                            ConnectionRequestOutcome.TIMEOUT -> "${device.displayName} 沒有回應連線請求。"
                            else -> "連不到 ${device.displayName}。"
                        }
                    )
                }
            } catch (e: CancellationException) {
                pushEvent("已取消連線請求。")
                throw e
            } finally {
                _connectingTo.value = null
            }
        }
    }

    /** 使用者在「等待對方接受」對話框按了取消。 */
    fun cancelConnect() {
        connectJob?.cancel()
    }

    fun dismissConnectFailure() {
        _connectFailure.value = null
    }

    fun dismissCompletedTransfer() {
        _completedTransfer.value = null
    }

    /** [trust] 為 true 時(必須同時 [accept])把這台裝置加入信任清單,之後的連線請求不再詢問。 */
    fun respondToConnectionRequest(accept: Boolean, trust: Boolean = false) {
        val request = _pendingConnectionRequest.value
        request?.respond?.invoke(accept)
        _pendingConnectionRequest.value = null
        clearConnectionRequestNotification()

        if (accept && trust && request != null) {
            lifecycleScope.launch { settingsRepository.trustDevice(request.requesterName) }
        }
    }

    fun disconnectFromPeer() {
        connectionEngine.disconnect()
        _connectionState.value = ConnectionUiState.Idle
        _transferState.value = null
        _completedTransfer.value = null
        _transferHistory.value = emptyList()
        pushEvent("已中斷連線。")
    }

    val isFileTransferActive: Boolean get() = connectionEngine.isFileTransferActive

    fun sendFile(uri: Uri, name: String, size: Long) {
        if (connectionEngine.isFileTransferActive) {
            pushEvent("目前已有檔案傳輸正在進行,請稍後再試。")
            return
        }
        _completedTransfer.value = null
        _transferState.value = TransferUiState(name, "等待對方接受...", 0f)
        connectionEngine.sendFile(uri, name, size)
    }

    fun sendFiles(files: List<Triple<Uri, String, Long>>) {
        if (files.isEmpty()) return
        if (connectionEngine.isFileTransferActive) {
            pushEvent("目前已有檔案傳輸正在進行,請稍後再試。")
            return
        }
        val title = if (files.size == 1) files[0].second else "${files.size} 個檔案"
        _completedTransfer.value = null
        _transferState.value = TransferUiState(title, "等待對方接受...", 0f)
        connectionEngine.sendFiles(files)
    }

    fun sendFolder(folderName: String, entries: List<FolderEntry>) {
        if (connectionEngine.isFileTransferActive) {
            pushEvent("目前已有檔案傳輸正在進行,請稍後再試。")
            return
        }
        _completedTransfer.value = null
        _transferState.value = TransferUiState(folderName, "等待對方接受...", 0f)
        connectionEngine.sendFolder(folderName, entries)
    }

    fun cancelTransfer() = connectionEngine.cancelFileTransfer()

    /** 開始把 [treeUri](SAF 選取的資料夾)裡的影片分享出去,並透過 mDNS 廣播讓其他裝置找得到。 */
    fun startVideoServer(treeUri: Uri) {
        lifecycleScope.launch {
            settingsRepository.setVideoShareFolderUri(treeUri.toString())
            val name = cachedSettings.deviceName
            val options = VideoServerPlaybackOptions(
                defaultSort = cachedSettings.videoDefaultSort,
                autoplayNext = cachedSettings.videoAutoplayNext,
                shuffle = cachedSettings.videoShuffle,
                defaultVolumePercent = cachedSettings.videoDefaultVolumePercent,
                defaultSpeed = cachedSettings.videoDefaultSpeed,
                autoplayCountdownSeconds = cachedSettings.videoAutoplayCountdownSeconds,
                extraExtensions = cachedSettings.videoExtraExtensions,
            )
            val started = videoHostServer.start(treeUri, name, options)
            if (started) {
                videoDiscovery.advertise(name, videoHostServer.port)
                _videoHostState.value = VideoHostUiState.Running(
                    port = videoHostServer.port,
                    videoCount = videoHostServer.manifestCount,
                    folderName = SafUtils.displayNameOf(applicationContext, treeUri),
                    url = NetworkUtils.lanIpv4Address()?.let { "http://$it:${videoHostServer.port}/" },
                )
                // 遙控面板的排序/隨機播放/自動播放沿用設定頁的影片伺服器預設值,之後在遙控畫面上的
                // 調整只影響這一次分享,不會寫回設定。
                _remotePlaylist.value = RemotePlaylistSettings(
                    sort = VideoSort.fromValue(options.defaultSort),
                    shuffle = options.shuffle,
                    shuffleSeed = Random.nextInt(),
                    autoplayNext = options.autoplayNext,
                )
                remoteAutoplayCountdownSeconds = options.autoplayCountdownSeconds
                remoteControlCancelAutoplay()
                startRemoteAutoplayWatcher()
            } else {
                _videoHostState.value = VideoHostUiState.Idle
            }
            _playbackState.value = playbackControlState.snapshot()
        }
    }

    fun stopVideoServer() {
        remoteAutoplayWatcher?.cancel()
        remoteAutoplayWatcher = null
        remoteControlCancelAutoplay()
        videoDiscovery.stopAdvertising()
        videoHostServer.stop()
        _videoHostState.value = VideoHostUiState.Idle
        _playbackState.value = playbackControlState.snapshot()
    }

    // ===================== 遠端控制模式(主機端遙控) =====================

    /** 主機端目前分享出去的影片庫,供遙控畫面選片用(直接沿用啟動時掃描好的 manifest,不重新掃描)。 */
    fun hostManifestEntries(): List<HostManifestEntry> = videoHostServer.manifest

    /** 供遙控面板畫時間軸用,見 [VideoHostServer.getDurationMs]。 */
    suspend fun hostVideoDurationMs(relativePath: String): Long? = videoHostServer.getDurationMs(relativePath)

    /** 即時計算目前播放位置(同一份「碼表」外插邏輯,供遙控畫面用本機輪詢方式顯示走動中的進度條,
     * 不用等下一次下指令才更新——這是同一個 process 內的呼叫,不用像觀眾端一樣打 HTTP)。 */
    fun currentPlaybackSnapshot(): PlaybackControlState.Snapshot = playbackControlState.snapshot()

    fun setRemoteControlEnabled(enabled: Boolean) {
        remoteControlCancelAutoplay()
        playbackControlState.setEnabled(enabled)
        _playbackState.value = playbackControlState.snapshot()
    }

    fun remoteControlPlayVideo(relativePath: String) {
        remoteControlCancelAutoplay()
        playbackControlState.setVideo(relativePath)
        _playbackState.value = playbackControlState.snapshot()
        // 讓外插的播放位置停在片尾:不知道長度的話,主機這邊的「碼表」在影片播完後還會一直往後跑,
        // 觀眾端會一直被要求跳到片尾之後的位置。探測結果有快取,遙控畫面畫時間軸時不會再探測一次。
        lifecycleScope.launch {
            val durationMs = videoHostServer.getDurationMs(relativePath)
            playbackControlState.setDuration(relativePath, durationMs)
            _playbackState.value = playbackControlState.snapshot()
        }
    }

    fun remoteControlSetPlaying(playing: Boolean) {
        remoteControlCancelAutoplay()
        playbackControlState.setPlaying(playing)
        _playbackState.value = playbackControlState.snapshot()
    }

    fun remoteControlSeek(positionMs: Long) {
        remoteControlCancelAutoplay()
        playbackControlState.seek(positionMs)
        _playbackState.value = playbackControlState.snapshot()
    }

    fun remoteControlSetPlaybackRate(rate: Double) {
        playbackControlState.setPlaybackRate(rate)
        _playbackState.value = playbackControlState.snapshot()
    }

    fun remoteControlSetVolume(volume: Double) {
        playbackControlState.setVolume(volume)
        _playbackState.value = playbackControlState.snapshot()
    }

    fun remoteControlSetMuted(muted: Boolean) {
        playbackControlState.setMuted(muted)
        _playbackState.value = playbackControlState.snapshot()
    }

    /** 依 [remotePlaylistEntries] 的順序切到上一部/下一部,供遙控畫面的上一部/下一部按鈕用——
     * 跟一般觀看端的上一部/下一部是同一種「在清單裡移動一格」的概念。 */
    fun remoteControlSkip(delta: Int) {
        remoteNeighborEntry(delta)?.let { remoteControlPlayVideo(it.relativePath) }
    }

    /** 遙控面板目前的播放清單順序:依 [remotePlaylist] 的排序方式(跟觀看頁共用
     * [VideoHostPages.displayOrder]),開了隨機播放就再用 seed 洗牌一次。 */
    fun remotePlaylistEntries(): List<HostManifestEntry> {
        val manifest = hostManifestEntries()
        val settings = _remotePlaylist.value
        return VideoHostPages.displayOrder(manifest, settings.sort, settings.shuffle, settings.shuffleSeed).map { manifest[it] }
    }

    fun remoteControlSetSort(sort: VideoSort) {
        _remotePlaylist.update { it.copy(sort = sort) }
    }

    /** 每次重新開啟都換一組 seed,不然關掉再打開會得到跟上次一模一樣的「隨機」順序。 */
    fun remoteControlSetShuffle(enabled: Boolean) {
        _remotePlaylist.update { it.copy(shuffle = enabled, shuffleSeed = if (enabled) Random.nextInt() else it.shuffleSeed) }
    }

    fun remoteControlSetAutoplayNext(enabled: Boolean) {
        _remotePlaylist.update { it.copy(autoplayNext = enabled) }
        if (!enabled) remoteControlCancelAutoplay()
    }

    fun remoteControlCancelAutoplay() {
        _remoteAutoplayCountdown.value = null
    }

    /** 目前選片在播放清單裡往前/往後 [delta] 格的影片;還沒選片時從第一部開始,超出範圍回傳 null。 */
    private fun remoteNeighborEntry(delta: Int): HostManifestEntry? {
        val playlist = remotePlaylistEntries()
        if (playlist.isEmpty()) return null
        val currentPath = playbackControlState.snapshot().videoRelativePath
        val currentIndex = playlist.indexOfFirst { it.relativePath == currentPath }
        return playlist.getOrNull(if (currentIndex < 0) 0 else currentIndex + delta)
    }

    private fun startRemoteAutoplayWatcher() {
        remoteAutoplayWatcher?.cancel()
        remoteAutoplayWatcher = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                checkRemoteAutoplay()
            }
        }
    }

    /**
     * 遙控模式下目前選片播到片尾時(總長度探測結果見 [remoteControlPlayVideo]),先把狀態定在片尾、
     * 改成暫停([PlaybackControlState.stopIfReachedEnd]),開了自動播放而且清單還有下一部就開始倒數,
     * 倒數結束才切過去。總長度探測失敗時無從判斷播完了沒有,這種影片就不會自動接下一部。
     */
    private fun checkRemoteAutoplay() {
        val countdown = _remoteAutoplayCountdown.value
        if (countdown != null) {
            val secondsLeft = ceil((remoteAutoplayDeadline - SystemClock.elapsedRealtime()) / 1000.0).toInt()
            if (secondsLeft <= 0) {
                remoteControlPlayVideo(countdown.nextRelativePath)
            } else if (secondsLeft != countdown.secondsLeft) {
                _remoteAutoplayCountdown.value = countdown.copy(secondsLeft = secondsLeft)
            }
            return
        }

        if (!playbackControlState.snapshot().enabled || !playbackControlState.stopIfReachedEnd()) return
        _playbackState.value = playbackControlState.snapshot()

        if (!_remotePlaylist.value.autoplayNext) return
        val next = remoteNeighborEntry(1) ?: return
        remoteAutoplayDeadline = SystemClock.elapsedRealtime() + remoteAutoplayCountdownSeconds * 1000L
        _remoteAutoplayCountdown.value = RemoteAutoplayCountdown(next.relativePath, next.name, remoteAutoplayCountdownSeconds)
    }

    // ===================== 內部事件轉接 =====================

    private fun wireCallbacks() {
        deviceDiscovery.onDeviceDiscovered = { device ->
            _devices.update { upsert(it, device) }
            pushLog("找到裝置:${device.displayName} (${device.host}:${device.port})")
        }
        deviceDiscovery.onDeviceRemoved = { name ->
            _devices.update { list -> list.filterNot { it.key == name } }
        }
        deviceDiscovery.onStatusChanged = { pushLog(it) }

        videoDiscovery.onDeviceDiscovered = { device ->
            _videoServers.update { upsert(it, device) }
            pushLog("找到影片伺服器:${device.displayName} (${device.host}:${device.port})")
        }
        videoDiscovery.onDeviceRemoved = { name ->
            _videoServers.update { list -> list.filterNot { it.key == name } }
        }
        videoDiscovery.onStatusChanged = { pushLog(it) }

        videoHostServer.onStatusChanged = { pushLog(it) }

        connectionEngine.onStatusChanged = { pushLog(it) }

        connectionEngine.onConnectionRequested = { request ->
            val isTrusted = cachedSettings.trustedDeviceNames.any { it.equals(request.requesterName, ignoreCase = true) }
            if (isTrusted) {
                request.respond(true)
                pushLog("已自動接受信任裝置「${request.requesterName}」的連線請求。")
            } else {
                _pendingConnectionRequest.value = request
                // 連線請求本身還是要使用者手動接受/拒絕(跟已經同意過的檔案傳輸不同),
                // 但確認對話框只有 App 開著才看得到,所以額外跳一則通知提醒使用者去開 App 處理。
                notifyConnectionRequest(request.requesterName)
            }
        }

        connectionEngine.onConnected = { peer ->
            _pendingConnectionRequest.value = null
            clearConnectionRequestNotification()
            _connectionState.value = ConnectionUiState.Connected(peer)
            _transferHistory.value = emptyList()
            _completedTransfer.value = null
            pushLog("已與 ${peer.name} (${peer.address}) 建立連線。")
            notifyConnected(peer.name)
        }

        connectionEngine.onRemoteDisconnected = {
            val peerName = (_connectionState.value as? ConnectionUiState.Connected)?.peer?.name
            _connectionState.value = ConnectionUiState.Idle
            _transferState.value = null
            _completedTransfer.value = null
            _transferHistory.value = emptyList()
            pushEvent("對方已中斷連線。")
            notifyPeerDisconnected(peerName ?: getString(R.string.notification_incoming_transfer_unknown_peer))
        }

        // 連線本身已經是使用者同意過的,連線建立後的檔案/資料夾傳送不再重複跳出確認對話框,
        // 直接接受並用系統通知讓使用者知道有東西正在傳進來(尤其是 App 在背景時)。
        connectionEngine.onFileOffered = { offer ->
            connectionEngine.respondToFileOffer(offer.transferId, true)
            _completedTransfer.value = null
            _transferState.value = TransferUiState(offer.fileName, "接收中...", 0f)
            notifyIncomingTransfer(offer.fileName)
        }
        connectionEngine.onFolderOffered = { offer ->
            connectionEngine.respondToFolderOffer(offer.transferId, true)
            _completedTransfer.value = null
            val title = if (offer.isBatch) "${offer.totalEntries} 個檔案" else offer.folderName
            _transferState.value = TransferUiState(title, "接收中...(共 ${offer.totalEntries} 個檔案)", 0f)
            notifyIncomingTransfer(title)
        }

        connectionEngine.onFileTransferProgress = { p ->
            _transferState.update { current ->
                val title = current?.title ?: (p.fileName ?: "傳輸中")
                if (p.folderTransferId != null && p.totalEntries != null) {
                    val transferred = p.folderBytesTransferred ?: p.bytesTransferred
                    val total = p.folderTotalBytes ?: p.totalBytes
                    val fraction = if (total > 0) transferred.toFloat() / total else 1f
                    TransferUiState(
                        title,
                        "第 ${p.entryIndex}/${p.totalEntries} 個檔案:${p.fileName}(${formatBytes(transferred)} / ${formatBytes(total)})",
                        fraction,
                    )
                } else {
                    val fraction = if (p.totalBytes > 0) p.bytesTransferred.toFloat() / p.totalBytes else 1f
                    TransferUiState(title, "${formatBytes(p.bytesTransferred)} / ${formatBytes(p.totalBytes)}", fraction)
                }
            }
        }

        connectionEngine.onFileTransferEnded = { ended ->
            _transferState.value = null
            clearIncomingTransferNotification()
            recordTransfer(
                TransferRecord(ended.direction, ended.reason, ended.fileName, isFolder = false, savedUri = ended.savedUri)
            )
            pushEvent(formatFileEndedMessage(ended))
        }

        connectionEngine.onFolderTransferEnded = { ended ->
            _transferState.value = null
            clearIncomingTransferNotification()
            recordTransfer(
                TransferRecord(
                    ended.direction, ended.reason,
                    if (ended.isBatch) "${ended.entryCount} 個檔案" else ended.folderName,
                    isFolder = true,
                )
            )
            pushEvent(formatFolderEndedMessage(ended))
        }
    }

    private fun recordTransfer(record: TransferRecord) {
        _transferHistory.update { (listOf(record) + it).take(MAX_TRANSFER_HISTORY) }
        _completedTransfer.value = if (record.canOpen) record else null
    }

    private fun upsert(list: List<DiscoveredDevice>, device: DiscoveredDevice): List<DiscoveredDevice> {
        val idx = list.indexOfFirst { it.key == device.key }
        return if (idx >= 0) list.toMutableList().also { it[idx] = device } else list + device
    }

    private fun pushLog(message: String) {
        _logMessages.update { (it + message).takeLast(200) }
    }

    private fun pushEvent(message: String) {
        pushLog(message)
        _events.tryEmit(message)
    }

    private fun formatFileEndedMessage(e: FileTransferEnded): String = when (e.reason) {
        TransferEndReason.COMPLETED -> if (e.direction == TransferDirection.RECEIVING) {
            "已收到檔案「${e.fileName}」,已儲存到 $downloadDisplayPath。"
        } else {
            "「${e.fileName}」傳送完成。"
        }
        TransferEndReason.REJECTED -> "對方拒絕接收「${e.fileName}」。"
        TransferEndReason.CANCELLED -> "已取消「${e.fileName}」的傳輸。"
        TransferEndReason.CANCELLED_BY_REMOTE -> "對方取消了「${e.fileName}」的傳輸。"
        TransferEndReason.CONNECTION_CLOSED -> "連線已中斷,「${e.fileName}」的傳輸已中止。"
        TransferEndReason.FAILED -> "「${e.fileName}」傳輸失敗。"
    }

    private fun formatFolderEndedMessage(e: FolderTransferEnded): String = if (e.isBatch) {
        when (e.reason) {
            TransferEndReason.COMPLETED -> if (e.direction == TransferDirection.RECEIVING) {
                "已收到 ${e.entryCount} 個檔案,已儲存到 $downloadDisplayPath。"
            } else {
                "${e.entryCount} 個檔案傳送完成。"
            }
            TransferEndReason.REJECTED -> "對方拒絕接收這些檔案。"
            TransferEndReason.CANCELLED -> "已取消傳輸(已完成 ${e.entryCount} 個檔案)。"
            TransferEndReason.CANCELLED_BY_REMOTE -> "對方取消了傳輸(已完成 ${e.entryCount} 個檔案)。"
            TransferEndReason.CONNECTION_CLOSED -> "連線已中斷,傳輸已中止(已完成 ${e.entryCount} 個檔案)。"
            TransferEndReason.FAILED -> "傳輸失敗(已完成 ${e.entryCount} 個檔案)。"
        }
    } else {
        when (e.reason) {
            TransferEndReason.COMPLETED -> if (e.direction == TransferDirection.RECEIVING) {
                "已收到資料夾「${e.folderName}」(共 ${e.entryCount} 個檔案),已儲存到 ${e.savedFolderPath ?: downloadDisplayPath}。"
            } else {
                "資料夾「${e.folderName}」傳送完成(共 ${e.entryCount} 個檔案)。"
            }
            TransferEndReason.REJECTED -> "對方拒絕接收資料夾「${e.folderName}」。"
            TransferEndReason.CANCELLED -> "已取消資料夾「${e.folderName}」的傳輸(已完成 ${e.entryCount} 個檔案)。"
            TransferEndReason.CANCELLED_BY_REMOTE -> "對方取消了資料夾「${e.folderName}」的傳輸(已完成 ${e.entryCount} 個檔案)。"
            TransferEndReason.CONNECTION_CLOSED -> "連線已中斷,資料夾「${e.folderName}」的傳輸已中止(已完成 ${e.entryCount} 個檔案)。"
            TransferEndReason.FAILED -> "資料夾「${e.folderName}」傳輸失敗(已完成 ${e.entryCount} 個檔案)。"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}" else String.format("%.1f %s", value, units[unitIndex])
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        // 傳輸提議/對方斷線不再跳確認對話框或需要開著 App 才看得到(連線本身已經是使用者
        // 同意過的),改用這個獨立頻道即時通知使用者。必須是 IMPORTANCE_HIGH 才會跳出橫幅
        // (heads-up)提醒,IMPORTANCE_DEFAULT 只會安靜地躺在通知欄裡。頻道一旦建立,之後改
        // 重要性不會生效(系統只認第一次建立時的設定,使用者要自己去系統設定調整),所以這裡
        // 用新的頻道 ID,避免舊版本(當初用 IMPORTANCE_DEFAULT 建立過)裝置上的使用者永遠看不到橫幅。
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID, getString(R.string.notification_alert_channel_name), NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(alertChannel)

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ConnectItService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.notification_action_stop), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * 檢查權限並發出通知,兩者必須寫在同一個函式裡——lint 的 MissingPermission 檢查只認得
     * 「檢查跟呼叫在同一段程式碼裡」這種寫法,拆到另一個函式(例如 hasPermission())它就認不出來了。
     */
    private fun postAlertNotification(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // 使用者拒絕了通知權限,安靜地放棄——對應的功能(自動接受傳輸/斷線處理)不受影響。
            return
        }
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    /** 收到檔案/資料夾提議並自動接受時呼叫,讓使用者(尤其是 App 在背景時)知道現在有東西傳進來。 */
    private fun notifyIncomingTransfer(itemName: String) {
        if (!cachedSettings.notificationsEnabled) return
        val peerName = (_connectionState.value as? ConnectionUiState.Connected)?.peer?.name
            ?: getString(R.string.notification_incoming_transfer_unknown_peer)

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_incoming_transfer_title, peerName))
            .setContentText(itemName)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 對應頻道的 IMPORTANCE_HIGH:pre-Oreo 裝置沒有頻道概念,重要性完全看這裡設的
            // PRIORITY_HIGH 才會跳橫幅。Oreo 以上頻道的設定才是真正生效的來源,這裡是保險。
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        postAlertNotification(TRANSFER_NOTIFICATION_ID, notification)
    }

    private fun clearIncomingTransferNotification() {
        NotificationManagerCompat.from(this).cancel(TRANSFER_NOTIFICATION_ID)
    }

    /** 連線交握完成時呼叫(不論是發起方還是被連線方),讓使用者(尤其是 App 在背景時)知道連線已經建立。 */
    private fun notifyConnected(peerName: String) {
        if (!cachedSettings.notificationsEnabled) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_connected_title, peerName))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        postAlertNotification(CONNECTED_NOTIFICATION_ID, notification)
    }

    /** 對方中斷連線時呼叫(非本機主動斷線),讓使用者(尤其是 App 在背景時)知道連線已經斷了。 */
    private fun notifyPeerDisconnected(peerName: String) {
        if (!cachedSettings.notificationsEnabled) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_peer_disconnected_title, peerName))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        postAlertNotification(DISCONNECTED_NOTIFICATION_ID, notification)
    }

    /**
     * 有裝置送出連線請求時呼叫。連線請求跟已經同意過的檔案傳輸不同,還是需要使用者手動
     * 接受/拒絕,但確認對話框只有 App 開著才看得到,所以用通知提醒使用者去開 App 處理。
     */
    private fun notifyConnectionRequest(requesterName: String) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_connection_request_title, requesterName))
            .setContentText(getString(R.string.notification_connection_request_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        postAlertNotification(CONNECTION_REQUEST_NOTIFICATION_ID, notification)
    }

    private fun clearConnectionRequestNotification() {
        NotificationManagerCompat.from(this).cancel(CONNECTION_REQUEST_NOTIFICATION_ID)
    }

    companion object {
        /** 通知上「關閉」按鈕送出的 Intent action,見 [onStartCommand]。 */
        const val ACTION_STOP = "com.connectit.android.service.ACTION_STOP"

        private const val CHANNEL_ID = "connectit_service"
        private const val NOTIFICATION_ID = 1
        // v2:原本只用來發傳輸提議通知、用 IMPORTANCE_DEFAULT 建立過,現在改成 IMPORTANCE_HIGH
        // 才能跳橫幅,也擴大用途涵蓋斷線通知。頻道 ID 一樣的話系統會沿用舊設定,所以要換一個
        // 新 ID 才會在已安裝過的裝置上真正生效。
        private const val ALERT_CHANNEL_ID = "connectit_alerts_v2"
        private const val TRANSFER_NOTIFICATION_ID = 2
        private const val DISCONNECTED_NOTIFICATION_ID = 3
        private const val CONNECTION_REQUEST_NOTIFICATION_ID = 4
        private const val CONNECTED_NOTIFICATION_ID = 5
        private const val MAX_TRANSFER_HISTORY = 50
    }
}
