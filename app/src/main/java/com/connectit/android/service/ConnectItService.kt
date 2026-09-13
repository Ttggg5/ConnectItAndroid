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
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.FileTransferEnded
import com.connectit.android.model.FolderEntry
import com.connectit.android.model.FolderTransferEnded
import com.connectit.android.model.TransferDirection
import com.connectit.android.model.TransferEndReason
import com.connectit.android.repo.SettingsRepository
import com.connectit.android.util.DownloadStorage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionUiState {
    data object Idle : ConnectionUiState
    data class Connected(val peer: ConnectedPeer) : ConnectionUiState
}

data class TransferUiState(
    val title: String,
    val statusText: String,
    val progress: Float,
)

/**
 * 背景前景服務,對應 Windows 端「關閉視窗會隱藏到系統匣但持續在背景運作」的概念:
 * 持有裝置配對用的 mDNS 廣播/搜尋、TCP 監聽與連線交握/檔案傳輸(見 [ConnectionEngine]),
 * 以及影片伺服器的 mDNS 搜尋(Android 端目前只作為觀看端,不從手機分享影片)。
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

    /** 顯示給使用者看的接收檔案位置(見 [com.connectit.android.util.DownloadStorage])。 */
    val downloadDisplayPath: String = DownloadStorage.DISPLAY_ROOT

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _videoServers = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val videoServers: StateFlow<List<DiscoveredDevice>> = _videoServers.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Idle)
    val connectionState: StateFlow<ConnectionUiState> = _connectionState.asStateFlow()

    private val _pendingConnectionRequest = MutableStateFlow<ConnectionRequest?>(null)
    val pendingConnectionRequest: StateFlow<ConnectionRequest?> = _pendingConnectionRequest.asStateFlow()

    private val _transferState = MutableStateFlow<TransferUiState?>(null)
    val transferState: StateFlow<TransferUiState?> = _transferState.asStateFlow()

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
        connectionEngine = ConnectionEngine(contentResolver, lifecycleScope)

        wireCallbacks()

        deviceDiscovery.startDiscovery()
        videoDiscovery.startDiscovery()

        lifecycleScope.launch {
            val port = connectionEngine.startListening()
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
        connectionEngine.dispose()
        super.onDestroy()
    }

    // ===================== 對外操作 API =====================

    fun refreshDevices() = deviceDiscovery.refresh()

    fun connectTo(device: DiscoveredDevice, myDeviceName: String) {
        lifecycleScope.launch {
            val accepted = connectionEngine.requestConnection(device.host, device.port, myDeviceName, device.displayName)
            if (!accepted) {
                pushEvent("${device.displayName} 未接受連線請求。")
            }
        }
    }

    fun respondToConnectionRequest(accept: Boolean) {
        _pendingConnectionRequest.value?.respond?.invoke(accept)
        _pendingConnectionRequest.value = null
        clearConnectionRequestNotification()
    }

    fun disconnectFromPeer() {
        connectionEngine.disconnect()
        _connectionState.value = ConnectionUiState.Idle
        _transferState.value = null
        pushEvent("已中斷連線。")
    }

    val isFileTransferActive: Boolean get() = connectionEngine.isFileTransferActive

    fun sendFile(uri: Uri, name: String, size: Long) {
        if (connectionEngine.isFileTransferActive) {
            pushEvent("目前已有檔案傳輸正在進行,請稍後再試。")
            return
        }
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
        _transferState.value = TransferUiState(title, "等待對方接受...", 0f)
        connectionEngine.sendFiles(files)
    }

    fun sendFolder(folderName: String, entries: List<FolderEntry>) {
        if (connectionEngine.isFileTransferActive) {
            pushEvent("目前已有檔案傳輸正在進行,請稍後再試。")
            return
        }
        _transferState.value = TransferUiState(folderName, "等待對方接受...", 0f)
        connectionEngine.sendFolder(folderName, entries)
    }

    fun cancelTransfer() = connectionEngine.cancelFileTransfer()

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

        connectionEngine.onStatusChanged = { pushLog(it) }

        connectionEngine.onConnectionRequested = { request ->
            _pendingConnectionRequest.value = request
            // 連線請求本身還是要使用者手動接受/拒絕(跟已經同意過的檔案傳輸不同),
            // 但確認對話框只有 App 開著才看得到,所以額外跳一則通知提醒使用者去開 App 處理。
            notifyConnectionRequest(request.requesterName)
        }

        connectionEngine.onConnected = { peer ->
            _pendingConnectionRequest.value = null
            clearConnectionRequestNotification()
            _connectionState.value = ConnectionUiState.Connected(peer)
            pushLog("已與 ${peer.name} (${peer.address}) 建立連線。")
        }

        connectionEngine.onRemoteDisconnected = {
            val peerName = (_connectionState.value as? ConnectionUiState.Connected)?.peer?.name
            _connectionState.value = ConnectionUiState.Idle
            _transferState.value = null
            pushEvent("對方已中斷連線。")
            notifyPeerDisconnected(peerName ?: getString(R.string.notification_incoming_transfer_unknown_peer))
        }

        // 連線本身已經是使用者同意過的,連線建立後的檔案/資料夾傳送不再重複跳出確認對話框,
        // 直接接受並用系統通知讓使用者知道有東西正在傳進來(尤其是 App 在背景時)。
        connectionEngine.onFileOffered = { offer ->
            connectionEngine.respondToFileOffer(offer.transferId, true)
            _transferState.value = TransferUiState(offer.fileName, "接收中...", 0f)
            notifyIncomingTransfer(offer.fileName)
        }
        connectionEngine.onFolderOffered = { offer ->
            connectionEngine.respondToFolderOffer(offer.transferId, true)
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
            pushEvent(formatFileEndedMessage(ended))
        }

        connectionEngine.onFolderTransferEnded = { ended ->
            _transferState.value = null
            clearIncomingTransferNotification()
            pushEvent(formatFolderEndedMessage(ended))
        }
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

    /** 對方中斷連線時呼叫(非本機主動斷線),讓使用者(尤其是 App 在背景時)知道連線已經斷了。 */
    private fun notifyPeerDisconnected(peerName: String) {
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
    }
}
