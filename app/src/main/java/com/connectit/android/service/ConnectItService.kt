package com.connectit.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.connectit.android.MainActivity
import com.connectit.android.R
import com.connectit.android.connection.ConnectionEngine
import com.connectit.android.discovery.NsdDiscoveryManager
import com.connectit.android.model.ConnectedPeer
import com.connectit.android.model.ConnectionRequest
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.FileOffer
import com.connectit.android.model.FileTransferEnded
import com.connectit.android.model.FolderEntry
import com.connectit.android.model.FolderOffer
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

    private val _pendingFileOffer = MutableStateFlow<FileOffer?>(null)
    val pendingFileOffer: StateFlow<FileOffer?> = _pendingFileOffer.asStateFlow()

    private val _pendingFolderOffer = MutableStateFlow<FolderOffer?>(null)
    val pendingFolderOffer: StateFlow<FolderOffer?> = _pendingFolderOffer.asStateFlow()

    private val _transferState = MutableStateFlow<TransferUiState?>(null)
    val transferState: StateFlow<TransferUiState?> = _transferState.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

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

    fun respondToFileOffer(transferId: String, accept: Boolean) {
        val offer = _pendingFileOffer.value
        _pendingFileOffer.value = null
        connectionEngine.respondToFileOffer(transferId, accept)
        if (accept && offer != null) {
            _transferState.value = TransferUiState(offer.fileName, "接收中...", 0f)
        }
    }

    fun respondToFolderOffer(transferId: String, accept: Boolean) {
        val offer = _pendingFolderOffer.value
        _pendingFolderOffer.value = null
        connectionEngine.respondToFolderOffer(transferId, accept)
        if (accept && offer != null) {
            val title = if (offer.isBatch) "${offer.totalEntries} 個檔案" else offer.folderName
            _transferState.value = TransferUiState(title, "接收中...(共 ${offer.totalEntries} 個檔案)", 0f)
        }
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
        }

        connectionEngine.onConnected = { peer ->
            _pendingConnectionRequest.value = null
            _connectionState.value = ConnectionUiState.Connected(peer)
            pushLog("已與 ${peer.name} (${peer.address}) 建立連線。")
        }

        connectionEngine.onRemoteDisconnected = {
            _connectionState.value = ConnectionUiState.Idle
            _transferState.value = null
            pushEvent("對方已中斷連線。")
        }

        connectionEngine.onFileOffered = { offer -> _pendingFileOffer.value = offer }
        connectionEngine.onFolderOffered = { offer -> _pendingFolderOffer.value = offer }

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
            if (_pendingFileOffer.value?.transferId == ended.transferId) _pendingFileOffer.value = null
            _transferState.value = null
            pushEvent(formatFileEndedMessage(ended))
        }

        connectionEngine.onFolderTransferEnded = { ended ->
            if (_pendingFolderOffer.value?.transferId == ended.transferId) _pendingFolderOffer.value = null
            _transferState.value = null
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

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "connectit_service"
        private const val NOTIFICATION_ID = 1
    }
}
