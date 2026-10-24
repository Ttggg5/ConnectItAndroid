package com.connectit.android.connection

import android.content.Context
import android.net.Uri
import com.connectit.android.model.ConnectionRequest
import com.connectit.android.model.ConnectionRequestOutcome
import com.connectit.android.model.FileOffer
import com.connectit.android.model.FileTransferEnded
import com.connectit.android.model.FolderOffer
import com.connectit.android.model.FolderTransferEnded
import com.connectit.android.model.TransferProgress
import com.connectit.android.net.ConnectMessage
import com.connectit.android.net.ScreenMessage
import com.connectit.android.net.readLine
import com.connectit.android.net.writeLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val REQUEST_TIMEOUT_MS = 10_000
private const val RESPONSE_TIMEOUT_MS = 60_000
private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000

/**
 * mDNS 找到裝置之後的實際 TCP 連線邏輯:監聽、連線請求/接受/拒絕交握。交握成功後每台裝置各有一個
 * [PeerConnection](檔案/資料夾傳輸訊框協定、心跳斷線偵測、螢幕鏡像訊框都在那裡),所以可以同時連線
 * 多台裝置。逐一對應 Windows 端的 ConnectionService.cs / PeerSession.cs,詳細協定說明見該檔案
 * 與 [com.connectit.android.net] 底下幾個檔案開頭的註解。
 *
 * 純邏輯類別,不是 Android Service 本身——由 [com.connectit.android.service.ConnectItService]
 * 持有一個實例並把事件轉接到 UI 可觀察的狀態上。各個 peer 的事件都會帶上是哪一個 [PeerConnection]。
 */
class ConnectionEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** 主動連線逾時秒數,可從設定頁調整(見 [com.connectit.android.repo.SettingsRepository])。 */
    @Volatile var connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS

    /** 使用者透過 SAF 選取的自訂接收資料夾,null 代表用預設的公用 Download/ConnectIt 資料夾。 */
    var customDownloadFolderProvider: () -> Uri? = { null }

    /** 這台裝置的穩定識別碼(見 SettingsRepository.deviceId),交握時送給對方。 */
    @Volatile var localDeviceId: String = UUID.randomUUID().toString().replace("-", "")

    var onConnectionRequested: ((ConnectionRequest) -> Unit)? = null
    var onConnected: ((PeerConnection) -> Unit)? = null

    /** 某一台裝置的連線結束;第二個參數為 true 代表是對方斷線(或心跳逾時),false 代表本機中斷。 */
    var onPeerClosed: ((PeerConnection, Boolean) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onFileOffered: ((PeerConnection, FileOffer) -> Unit)? = null
    var onFolderOffered: ((PeerConnection, FolderOffer) -> Unit)? = null
    var onFileTransferProgress: ((PeerConnection, TransferProgress) -> Unit)? = null
    var onFileTransferEnded: ((PeerConnection, FileTransferEnded) -> Unit)? = null
    var onFolderTransferEnded: ((PeerConnection, FolderTransferEnded) -> Unit)? = null

    /** 收到螢幕鏡像控制訊息(見 [ScreenMessage]),在讀取迴圈上呼叫,處理時不能阻塞太久。 */
    var onScreenMessage: ((PeerConnection, ScreenMessage) -> Unit)? = null

    /** 收到一張螢幕畫面:payload 是 [序號 4 bytes][JPEG],在讀取迴圈上呼叫,解碼請另外開工作。 */
    var onScreenFrame: ((PeerConnection, ByteArray) -> Unit)? = null

    /** 收到一段螢幕鏡像的聲音(16-bit PCM),在讀取迴圈上呼叫,不能阻塞。 */
    var onScreenAudio: ((PeerConnection, ByteArray) -> Unit)? = null

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _peers = CopyOnWriteArrayList<PeerConnection>()

    /** 目前所有已連線的裝置。 */
    val peers: List<PeerConnection> get() = _peers.toList()

    val port: Int get() = serverSocket?.localPort ?: -1
    val isConnected: Boolean get() = _peers.isNotEmpty()
    val isFileTransferActive: Boolean get() = _peers.any { it.isFileTransferActive }

    fun findPeer(peerId: String): PeerConnection? = _peers.firstOrNull { it.peerId == peerId }

    suspend fun startListening(preferredPort: Int = 0): Int = withContext(Dispatchers.IO) {
        stopListening()
        val server = try {
            ServerSocket(preferredPort)
        } catch (e: IOException) {
            if (preferredPort == 0) throw e
            onStatusChanged?.invoke("連接埠 $preferredPort 無法使用,已改用系統自動指派的連接埠。")
            ServerSocket(0)
        }
        serverSocket = server
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
        onStatusChanged?.invoke("已開始監聽連接埠 ${server.localPort}。")
        server.localPort
    }

    fun stopListening() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (scope.isActive) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                break
            }
            scope.launch(Dispatchers.IO) { handleIncoming(socket) }
        }
    }

    private suspend fun handleIncoming(socket: Socket) {
        val remoteAddress = socket.inetAddress?.hostAddress ?: "unknown"
        onStatusChanged?.invoke("收到來自 $remoteAddress 的連線嘗試。")

        try {
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val line = try {
                readLine(input)
            } catch (e: SocketTimeoutException) {
                null
            }
            val request = line?.let { ConnectMessage.parse(it) }

            if (request == null || request.type != "request") {
                socket.closeQuietly()
                return
            }

            val requesterName = request.deviceName?.takeIf { it.isNotBlank() } ?: remoteAddress

            val accepted = suspendCancellableCoroutine<Boolean> { cont ->
                val handler = onConnectionRequested
                if (handler == null) {
                    cont.resumeWith(Result.success(false))
                } else {
                    handler(ConnectionRequest(requesterName, remoteAddress, request.deviceId) { result ->
                        if (cont.isActive) cont.resumeWith(Result.success(result))
                    })
                }
            }

            socket.soTimeout = 0
            writeLine(output, ConnectMessage(type = if (accepted) "accept" else "reject", deviceId = localDeviceId).toJson())

            if (!accepted) {
                socket.closeQuietly()
                return
            }

            addPeer(socket, requesterName, remoteAddress, request.deviceId)
        } catch (e: Exception) {
            onStatusChanged?.invoke("處理連入連線時發生錯誤:${e.message}")
            socket.closeQuietly()
        }
    }

    /**
     * 主動連線並送出連線請求,等待對方確認,回傳詳細結果供 UI 顯示對應的說明。
     * 呼叫端的 coroutine 被取消時(使用者在「等待對方接受」對話框按了取消),會關閉這條尚未建立完成的
     * socket 讓阻塞中的連線/讀取立刻中斷,並照一般 coroutine 取消流程丟出 CancellationException。
     */
    suspend fun requestConnection(
        address: String,
        port: Int,
        myDeviceName: String,
        remoteDisplayName: String,
    ): ConnectionRequestOutcome = withContext(Dispatchers.IO) {
        val socket = Socket()
        // 阻塞式的 connect/read 不會理會 coroutine 取消,取消時直接關掉 socket 讓它們丟例外結束。
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) socket.closeQuietly()
        }
        try {
            try {
                socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            } catch (e: Exception) {
                ensureActive()
                onStatusChanged?.invoke("連線失敗:${e.message}")
                socket.closeQuietly()
                return@withContext ConnectionRequestOutcome.UNREACHABLE
            }
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            onStatusChanged?.invoke("已送出連線請求給 $remoteDisplayName,等待對方確認...")
            writeLine(output, ConnectMessage(type = "request", deviceName = myDeviceName, deviceId = localDeviceId).toJson())

            socket.soTimeout = RESPONSE_TIMEOUT_MS
            var timedOut = false
            val line = try {
                readLine(input)
            } catch (e: SocketTimeoutException) {
                timedOut = true
                null
            }
            ensureActive()
            val response = line?.let { ConnectMessage.parse(it) }

            if (response?.type == "accept") {
                socket.soTimeout = 0
                // 連線成功後這條 socket 要繼續用,不能再被上面的取消處理關掉。
                cancelHandle?.dispose()
                addPeer(socket, remoteDisplayName, address, response.deviceId)
                ConnectionRequestOutcome.ACCEPTED
            } else {
                onStatusChanged?.invoke(
                    if (timedOut) "連線逾時,$remoteDisplayName 沒有回應。" else "$remoteDisplayName 拒絕了連線請求。"
                )
                socket.closeQuietly()
                if (timedOut) ConnectionRequestOutcome.TIMEOUT else ConnectionRequestOutcome.REJECTED
            }
        } catch (e: CancellationException) {
            socket.closeQuietly()
            throw e
        } catch (e: Exception) {
            ensureActive()
            onStatusChanged?.invoke("連線失敗:${e.message}")
            socket.closeQuietly()
            ConnectionRequestOutcome.UNREACHABLE
        } finally {
            cancelHandle?.dispose()
        }
    }

    /**
     * 登記一條剛完成交握的連線並開始監控。同一台裝置(相同 DeviceId)如果還留著一條舊連線——通常是對方
     * 斷線後重新連線,舊連線還沒被心跳判定斷線——先把舊的關掉,避免同一台裝置出現兩次。
     */
    private fun addPeer(socket: Socket, name: String, address: String, remoteDeviceId: String?) {
        val peer = PeerConnection(context, scope, socket, name, address, remoteDeviceId, customDownloadFolderProvider)
        val stale = if (remoteDeviceId == null) emptyList() else _peers.filter { it.remoteDeviceId == remoteDeviceId }
        _peers.add(peer)
        stale.forEach { it.disconnect() }

        peer.onStatusChanged = { onStatusChanged?.invoke(it) }
        peer.onFileOffered = { onFileOffered?.invoke(peer, it) }
        peer.onFolderOffered = { onFolderOffered?.invoke(peer, it) }
        peer.onFileTransferProgress = { onFileTransferProgress?.invoke(peer, it) }
        peer.onFileTransferEnded = { onFileTransferEnded?.invoke(peer, it) }
        peer.onFolderTransferEnded = { onFolderTransferEnded?.invoke(peer, it) }
        peer.onScreenMessage = { onScreenMessage?.invoke(peer, it) }
        peer.onScreenFrame = { onScreenFrame?.invoke(peer, it) }
        peer.onScreenAudio = { onScreenAudio?.invoke(peer, it) }
        peer.onClosed = { byRemote ->
            _peers.remove(peer)
            onPeerClosed?.invoke(peer, byRemote)
        }

        // 先通知畫面有新連線,再開始讀取:不然對方很快送來的訊息(例如檔案提議)或斷線,
        // 可能比「已連線」更早被處理到,畫面會找不到這台裝置。
        onConnected?.invoke(peer)
        peer.start()
    }

    /** 收到 [onFileOffered] 之後回應(依 TransferId 找到對應的連線)。 */
    fun respondToFileOffer(transferId: String, accept: Boolean) {
        _peers.firstOrNull { it.hasPendingOffer(transferId) }?.respondToFileOffer(transferId, accept)
    }

    /** 收到 [onFolderOffered] 之後回應(依 TransferId 找到對應的連線)。 */
    fun respondToFolderOffer(transferId: String, accept: Boolean) {
        _peers.firstOrNull { it.hasPendingOffer(transferId) }?.respondToFolderOffer(transferId, accept)
    }

    /** 中斷指定裝置的連線。 */
    fun disconnect(peerId: String) {
        findPeer(peerId)?.disconnect()
    }

    /** 中斷所有連線(不影響監聽)。 */
    fun disconnectAll() {
        _peers.forEach { it.disconnect() }
    }

    fun dispose() {
        disconnectAll()
        stopListening()
    }
}
