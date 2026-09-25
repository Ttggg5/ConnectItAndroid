package com.connectit.android.connection

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.connectit.android.model.ConnectedPeer
import com.connectit.android.model.ConnectionRequest
import com.connectit.android.model.FileOffer
import com.connectit.android.model.FileTransferEnded
import com.connectit.android.model.FolderEntry
import com.connectit.android.model.FolderOffer
import com.connectit.android.model.FolderTransferEnded
import com.connectit.android.model.TransferDirection
import com.connectit.android.model.TransferEndReason
import com.connectit.android.model.TransferProgress
import com.connectit.android.net.ConnectMessage
import com.connectit.android.net.FILE_CHUNK_SIZE
import com.connectit.android.net.FileControlMessage
import com.connectit.android.net.FrameType
import com.connectit.android.net.readFrame
import com.connectit.android.net.readLine
import com.connectit.android.net.writeFrame
import com.connectit.android.net.writeLine
import com.connectit.android.util.DownloadStorage
import com.connectit.android.util.SafUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

private const val REQUEST_TIMEOUT_MS = 10_000
private const val RESPONSE_TIMEOUT_MS = 60_000
private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
private const val PROGRESS_REPORT_INTERVAL_MS = 100L
private const val FRAME_HEADER_SIZE = 5
private const val SOCKET_READ_BUFFER_SIZE = 128 * 1024
// 接收端每收到一個 64KB 區塊就直接寫進 MediaStore/SAF 串流,在 Android 11+ 底下每次寫入都要
// 經過 FUSE,累積成較大的區塊再寫可以明顯減少這部分的開銷。
private const val RECEIVE_WRITE_BUFFER_SIZE = 1024 * 1024

/**
 * mDNS 找到裝置之後的實際 TCP 連線邏輯:連線請求/接受/拒絕交握,連線建立後的檔案/資料夾
 * 傳輸訊框協定,以及斷線偵測。逐一對應 Windows 端的 ConnectionService.cs,詳細協定說明見該檔案
 * 與 [com.connectit.android.net] 底下幾個檔案開頭的註解。
 *
 * 純邏輯類別,不是 Android Service 本身——由 [com.connectit.android.service.ConnectItService]
 * 持有一個實例並把事件轉接到 UI 可觀察的狀態上。
 */
class ConnectionEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val contentResolver: ContentResolver get() = context.contentResolver

    /** 主動連線逾時秒數,可從設定頁調整(見 [com.connectit.android.repo.SettingsRepository])。 */
    @Volatile var connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS

    /** 使用者透過 SAF 選取的自訂接收資料夾,null 代表用預設的公用 Download/ConnectIt 資料夾。 */
    var customDownloadFolderProvider: () -> Uri? = { null }

    var onConnectionRequested: ((ConnectionRequest) -> Unit)? = null
    var onConnected: ((ConnectedPeer) -> Unit)? = null
    var onRemoteDisconnected: (() -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onFileOffered: ((FileOffer) -> Unit)? = null
    var onFolderOffered: ((FolderOffer) -> Unit)? = null
    var onFileTransferProgress: ((TransferProgress) -> Unit)? = null
    var onFileTransferEnded: ((FileTransferEnded) -> Unit)? = null
    var onFolderTransferEnded: ((FolderTransferEnded) -> Unit)? = null

    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile private var activeSocket: Socket? = null
    private var monitorJob: Job? = null
    private val writeMutex = Mutex()

    /** 組裝訊框用的共用緩衝區,只在持有 [writeMutex] 時使用(見 [writeRaw])。 */
    private val frameScratch = ByteArray(FRAME_HEADER_SIZE + FILE_CHUNK_SIZE)

    @Volatile private var activeTransfer: ActiveTransferState? = null
    @Volatile private var activeFolderSession: FolderSessionState? = null

    val port: Int get() = serverSocket?.localPort ?: -1
    val isConnected: Boolean get() = activeSocket != null
    val isFileTransferActive: Boolean get() = activeTransfer != null || activeFolderSession != null

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
            if (activeSocket != null) {
                // 已經有進行中的連線,直接拒絕,避免同時存在多條連線導致狀態混亂。
                writeLine(socket.getOutputStream(), ConnectMessage(type = "reject").toJson())
                socket.closeQuietly()
                return
            }

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
                    handler(ConnectionRequest(requesterName, remoteAddress) { result ->
                        if (cont.isActive) cont.resumeWith(Result.success(result))
                    })
                }
            }

            socket.soTimeout = 0
            writeLine(output, ConnectMessage(type = if (accepted) "accept" else "reject").toJson())

            if (!accepted) {
                socket.closeQuietly()
                return
            }

            becomeConnected(socket, requesterName, remoteAddress)
        } catch (e: Exception) {
            onStatusChanged?.invoke("處理連入連線時發生錯誤:${e.message}")
            socket.closeQuietly()
        }
    }

    suspend fun requestConnection(
        address: String,
        port: Int,
        myDeviceName: String,
        remoteDisplayName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            onStatusChanged?.invoke("已送出連線請求給 $remoteDisplayName,等待對方確認...")
            writeLine(output, ConnectMessage(type = "request", deviceName = myDeviceName).toJson())

            socket.soTimeout = RESPONSE_TIMEOUT_MS
            val line = try {
                readLine(input)
            } catch (e: SocketTimeoutException) {
                null
            }
            val response = line?.let { ConnectMessage.parse(it) }

            if (response?.type == "accept") {
                socket.soTimeout = 0
                becomeConnected(socket, remoteDisplayName, address)
                true
            } else {
                onStatusChanged?.invoke(
                    if (response == null) "連線逾時,$remoteDisplayName 沒有回應。" else "$remoteDisplayName 拒絕了連線請求。"
                )
                socket.closeQuietly()
                false
            }
        } catch (e: Exception) {
            onStatusChanged?.invoke("連線失敗:${e.message}")
            socket.closeQuietly()
            false
        }
    }

    private fun becomeConnected(socket: Socket, name: String, address: String) {
        // 避免 TCP Nagle 演算法延遲小封包送出——與對方的 delayed ACK 交互作用下會嚴重拖慢傳輸速度。
        runCatching { socket.tcpNoDelay = true }
        activeSocket = socket
        startMonitoring(socket)
        onConnected?.invoke(ConnectedPeer(name, address))
    }

    fun disconnect() {
        monitorJob?.cancel()
        monitorJob = null
        activeSocket?.closeQuietly()
        activeSocket = null
    }

    private fun startMonitoring(socket: Socket) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            var remoteClosed = false
            try {
                // 加一層緩衝:每個訊框的 4+1 bytes 標頭如果直接對 socket 讀,每次都是一次系統呼叫。
                // 交握階段的 readLine 是逐 byte 讀、不會多讀,所以到這裡才包不會吃掉任何資料。
                val input = BufferedInputStream(socket.getInputStream(), SOCKET_READ_BUFFER_SIZE)
                while (isActive) {
                    val frame = readFrame(input)
                    if (frame == null) {
                        remoteClosed = true
                        break
                    }
                    when (frame.type) {
                        FrameType.CONTROL -> handleControlFrame(frame.payload)
                        FrameType.CHUNK -> handleChunkFrame(frame.payload)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) remoteClosed = true
            }

            abortActiveTransfer(TransferEndReason.CONNECTION_CLOSED)
            abortActiveFolderSession(TransferEndReason.CONNECTION_CLOSED)

            if (remoteClosed) {
                activeSocket = null
                onStatusChanged?.invoke("對方已中斷連線。")
                onRemoteDisconnected?.invoke()
            }
        }
    }

    // ===================== 訊框寫入 =====================

    private suspend fun writeControl(message: FileControlMessage) {
        val payload = message.toJson()
        writeRaw(FrameType.CONTROL, payload, payload.size)
    }

    private suspend fun writeChunk(buffer: ByteArray, length: Int) {
        writeRaw(FrameType.CHUNK, buffer, length)
    }

    private suspend fun writeRaw(type: Int, payload: ByteArray, length: Int) {
        val socket = activeSocket ?: return
        writeMutex.withLock {
            try {
                writeFrame(socket.getOutputStream(), type, payload, 0, length, frameScratch)
            } catch (e: Exception) {
                // 寫入失敗通常代表連線已經斷了,交給監控迴圈去處理斷線通知即可。
            }
        }
    }

    private fun launchWrite(message: FileControlMessage) {
        scope.launch(Dispatchers.IO) { writeControl(message) }
    }

    // ===================== 檔案/資料夾傳輸:送出 =====================

    fun sendFile(uri: Uri, fileName: String, size: Long) {
        if (!isConnected) {
            onStatusChanged?.invoke("尚未連線,無法傳送檔案。")
            return
        }
        if (isFileTransferActive) {
            onStatusChanged?.invoke("目前已有檔案傳輸正在進行,請稍後再試。")
            return
        }

        val transfer = ActiveTransferState(
            transferId = randomId(),
            direction = TransferDirection.SENDING,
            fileName = fileName,
            totalBytes = size,
            sourceUri = uri,
        )
        activeTransfer = transfer

        onStatusChanged?.invoke("已送出檔案「$fileName」的傳送請求,等待對方確認...")
        launchWrite(FileControlMessage(type = "file-offer", transferId = transfer.transferId, fileName = fileName, size = size))
    }

    /** 多個各自獨立的檔案,不建立子資料夾;只有一個檔案時直接走 [sendFile]。 */
    fun sendFiles(files: List<Triple<Uri, String, Long>>) {
        if (files.size == 1) {
            val (uri, name, size) = files[0]
            sendFile(uri, name, size)
            return
        }
        if (!isConnected) {
            onStatusChanged?.invoke("尚未連線,無法傳送檔案。")
            return
        }
        if (isFileTransferActive || files.isEmpty()) {
            if (files.isNotEmpty()) onStatusChanged?.invoke("目前已有檔案傳輸正在進行,請稍後再試。")
            return
        }

        val entries = ArrayDeque(files.map { (uri, name, size) -> FolderEntry(uri, name, size) })
        val session = FolderSessionState(
            folderTransferId = randomId(),
            direction = TransferDirection.SENDING,
            folderName = "${files.size} 個檔案",
            totalBytes = files.sumOf { it.third },
            totalEntries = files.size,
            isBatch = true,
            pendingFiles = entries,
        )
        activeFolderSession = session

        onStatusChanged?.invoke("已送出 ${session.totalEntries} 個檔案的傳送請求,等待對方確認...")
        launchWrite(
            FileControlMessage(
                type = "folder-offer",
                transferId = session.folderTransferId,
                fileName = session.folderName,
                size = session.totalBytes,
                totalEntries = session.totalEntries,
                isBatch = true,
            )
        )
    }

    fun sendFolder(folderName: String, entries: List<FolderEntry>) {
        if (!isConnected) {
            onStatusChanged?.invoke("尚未連線,無法傳送資料夾。")
            return
        }
        if (isFileTransferActive) {
            onStatusChanged?.invoke("目前已有檔案傳輸正在進行,請稍後再試。")
            return
        }
        if (entries.isEmpty()) {
            onStatusChanged?.invoke("資料夾是空的,沒有可以傳送的檔案。")
            return
        }

        val session = FolderSessionState(
            folderTransferId = randomId(),
            direction = TransferDirection.SENDING,
            folderName = folderName,
            totalBytes = entries.sumOf { it.size },
            totalEntries = entries.size,
            isBatch = false,
            pendingFiles = ArrayDeque(entries),
        )
        activeFolderSession = session

        onStatusChanged?.invoke("已送出資料夾「${session.folderName}」(${session.totalEntries} 個檔案)的傳送請求,等待對方確認...")
        launchWrite(
            FileControlMessage(
                type = "folder-offer",
                transferId = session.folderTransferId,
                fileName = session.folderName,
                size = session.totalBytes,
                totalEntries = session.totalEntries,
            )
        )
    }

    private fun sendFileChunksAsync(transfer: ActiveTransferState) {
        transfer.job = scope.launch(Dispatchers.IO) {
            try {
                val input = transfer.sourceUri?.let { contentResolver.openInputStream(it) }
                    ?: throw java.io.IOException("無法開啟檔案")
                input.use { stream ->
                    // 讀取檔案(生產者)跟寫入 socket(消費者)用有界 channel 重疊執行,而不是
                    // 完全循序的「讀一塊→寫一塊」——這樣寫入目前這塊的同時就能開始讀下一塊,
                    // 內容提供者較慢(例如雲端同步的檔案)時能明顯縮短整體傳輸時間。
                    // 用固定的一組緩衝區在 freeBuffers/filledChunks 兩個 channel 之間循環,
                    // 避免每個 chunk 都重新配置一塊 64KB 陣列造成不必要的 GC 壓力。
                    val bufferCount = 3
                    val freeBuffers = Channel<ByteArray>(bufferCount)
                    repeat(bufferCount) { freeBuffers.trySend(ByteArray(FILE_CHUNK_SIZE)) }
                    val filledChunks = Channel<Pair<ByteArray, Int>>(bufferCount)

                    var readFailure: Throwable? = null
                    val readJob = launch(Dispatchers.IO) {
                        try {
                            while (isActive) {
                                val buffer = freeBuffers.receive()
                                val read = try {
                                    stream.read(buffer)
                                } catch (e: Exception) {
                                    freeBuffers.trySend(buffer)
                                    throw e
                                }
                                if (read <= 0) {
                                    freeBuffers.trySend(buffer)
                                    break
                                }
                                filledChunks.send(buffer to read)
                            }
                        } catch (e: CancellationException) {
                            // 由消費者端(寫入失敗/外層取消)中止,不算讀取失敗。
                        } catch (e: Exception) {
                            readFailure = e
                        } finally {
                            filledChunks.close()
                        }
                    }

                    try {
                        for ((buffer, read) in filledChunks) {
                            writeChunk(buffer, read)
                            transfer.transferredBytes += read
                            reportProgress(transfer)
                            freeBuffers.trySend(buffer)
                        }
                    } finally {
                        // 不管上面的迴圈是正常結束還是中途丟例外,都要先讓背景讀取工作停下來,
                        // 才能安全關閉 input——否則讀取工作可能還卡在等 channel 有空位。
                        readJob.cancelAndJoin()
                    }
                    readFailure?.let { throw it }
                }
                onStatusChanged?.invoke("「${transfer.fileName}」已送出,等待對方確認接收完成...")
            } catch (e: CancellationException) {
                // 本機或對方取消,cancelFileTransfer()/handleFileCancel() 已經處理過清理跟事件了。
            } catch (e: Exception) {
                failActiveTransfer(transfer, "傳送檔案失敗:${e.message}")
            }
        }
    }

    private fun sendNextFolderFileOrComplete(session: FolderSessionState) {
        val next = session.pendingFiles?.removeFirstOrNull()
        if (next == null) {
            activeFolderSession = null
            launchWrite(FileControlMessage(type = "folder-complete", transferId = session.folderTransferId))
            onFolderTransferEnded?.invoke(
                FolderTransferEnded(session.folderTransferId, TransferDirection.SENDING, TransferEndReason.COMPLETED, session.folderName, session.totalEntries, session.isBatch)
            )
            return
        }

        val entryIndex = session.completedEntries + 1
        val transfer = ActiveTransferState(
            transferId = randomId(),
            direction = TransferDirection.SENDING,
            fileName = next.relativePath.substringAfterLast('/'),
            totalBytes = next.size,
            sourceUri = next.uri,
            folderTransferId = session.folderTransferId,
            entryIndex = entryIndex,
        )
        activeTransfer = transfer

        launchWrite(
            FileControlMessage(
                type = "file-offer",
                transferId = transfer.transferId,
                fileName = transfer.fileName,
                size = transfer.totalBytes,
                folderTransferId = session.folderTransferId,
                relativePath = next.relativePath,
                entryIndex = entryIndex,
                totalEntries = session.totalEntries,
            )
        )
    }

    // ===================== 檔案/資料夾傳輸:接收端回應提議 =====================

    fun respondToFileOffer(transferId: String, accept: Boolean) {
        val transfer = activeTransfer
        if (transfer == null || transfer.direction != TransferDirection.RECEIVING || transfer.transferId != transferId) return

        if (!accept) {
            activeTransfer = null
            launchWrite(FileControlMessage(type = "file-reject", transferId = transferId))
            onFileTransferEnded?.invoke(FileTransferEnded(transferId, TransferDirection.RECEIVING, TransferEndReason.REJECTED, transfer.fileName))
            return
        }

        val received = DownloadStorage.createFile(context, "", transfer.fileName, customDownloadFolderProvider())
        if (received == null) {
            activeTransfer = null
            launchWrite(FileControlMessage(type = "file-reject", transferId = transferId))
            onStatusChanged?.invoke("無法建立檔案。")
            onFileTransferEnded?.invoke(FileTransferEnded(transferId, TransferDirection.RECEIVING, TransferEndReason.FAILED, transfer.fileName))
            return
        }
        transfer.outputStream = received.outputStream.buffered(RECEIVE_WRITE_BUFFER_SIZE)
        transfer.savedToken = received.token
        transfer.savedDisplayPath = received.displayPath

        launchWrite(FileControlMessage(type = "file-accept", transferId = transferId))
    }

    fun respondToFolderOffer(transferId: String, accept: Boolean) {
        val session = activeFolderSession
        if (session == null || session.direction != TransferDirection.RECEIVING || session.folderTransferId != transferId) return

        if (!accept) {
            activeFolderSession = null
            launchWrite(FileControlMessage(type = "folder-reject", transferId = transferId))
            onFolderTransferEnded?.invoke(
                FolderTransferEnded(transferId, TransferDirection.RECEIVING, TransferEndReason.REJECTED, session.folderName, 0, session.isBatch)
            )
            return
        }

        // 批次(多個各自獨立的檔案)直接落在 Download/ConnectIt 根目錄;真正的資料夾傳輸則
        // 在底下多開一層以資料夾名稱命名的子目錄。MediaStore 插入時會自動建立所需的子目錄,
        // 不需要像檔案系統那樣自己先 mkdirs()。
        session.relativeSubDir = if (session.isBatch) "" else session.folderName

        launchWrite(FileControlMessage(type = "folder-accept", transferId = transferId))
    }

    fun cancelFileTransfer() {
        val session = activeFolderSession
        if (session != null) {
            activeFolderSession = null

            val currentEntry = activeTransfer
            if (currentEntry != null && currentEntry.folderTransferId != null) {
                activeTransfer = null
                cleanupTransferResources(currentEntry)
            }

            launchWrite(FileControlMessage(type = "folder-cancel", transferId = session.folderTransferId))
            onFolderTransferEnded?.invoke(
                FolderTransferEnded(session.folderTransferId, session.direction, TransferEndReason.CANCELLED, session.folderName, session.completedEntries, session.isBatch)
            )
            return
        }

        val transfer = activeTransfer ?: return
        activeTransfer = null
        cleanupTransferResources(transfer)

        launchWrite(FileControlMessage(type = "file-cancel", transferId = transfer.transferId))
        onFileTransferEnded?.invoke(FileTransferEnded(transfer.transferId, transfer.direction, TransferEndReason.CANCELLED, transfer.fileName))
    }

    // ===================== 控制訊框處理(接收端解析對方送來的訊息) =====================

    private suspend fun handleControlFrame(payload: ByteArray) {
        val message = FileControlMessage.parse(payload) ?: return
        if (message.transferId == null) return

        when (message.type) {
            "file-offer" -> handleFileOffer(message)
            "file-accept" -> handleFileAccept(message)
            "file-reject" -> handleFileReject(message)
            "file-cancel" -> handleFileCancel(message)
            "file-complete" -> handleFileCompleteAck(message)
            "folder-offer" -> handleFolderOffer(message)
            "folder-accept" -> handleFolderAccept(message)
            "folder-reject" -> handleFolderReject(message)
            "folder-cancel" -> handleFolderCancel(message)
            "folder-complete" -> handleFolderComplete(message)
        }
    }

    private suspend fun handleFileOffer(message: FileControlMessage) {
        if (message.folderTransferId != null) {
            handleFolderFileOffer(message)
            return
        }

        if (isFileTransferActive || message.fileName.isNullOrBlank() || message.size == null || message.size < 0) {
            writeControl(FileControlMessage(type = "file-reject", transferId = message.transferId))
            return
        }

        val transfer = ActiveTransferState(
            transferId = message.transferId!!,
            direction = TransferDirection.RECEIVING,
            fileName = sanitizeFileName(message.fileName),
            totalBytes = message.size,
        )
        activeTransfer = transfer

        onFileOffered?.invoke(FileOffer(transfer.transferId, transfer.fileName, transfer.totalBytes))
    }

    private suspend fun handleFolderOffer(message: FileControlMessage) {
        val totalEntries = message.totalEntries
        if (isFileTransferActive || message.fileName.isNullOrBlank() || message.size == null || message.size < 0
            || totalEntries == null || totalEntries <= 0
        ) {
            writeControl(FileControlMessage(type = "folder-reject", transferId = message.transferId))
            return
        }

        val isBatch = message.isBatch == true
        val session = FolderSessionState(
            folderTransferId = message.transferId!!,
            direction = TransferDirection.RECEIVING,
            folderName = if (isBatch) message.fileName else sanitizeFileName(message.fileName),
            totalBytes = message.size,
            totalEntries = totalEntries,
            isBatch = isBatch,
        )
        activeFolderSession = session

        onFolderOffered?.invoke(FolderOffer(session.folderTransferId, session.folderName, session.totalBytes, session.totalEntries, session.isBatch))
    }

    private suspend fun handleFolderFileOffer(message: FileControlMessage) {
        val session = activeFolderSession
        val baseSubDir = session?.relativeSubDir
        if (session == null || session.direction != TransferDirection.RECEIVING || baseSubDir == null
            || session.folderTransferId != message.folderTransferId || activeTransfer != null
            || message.fileName.isNullOrBlank() || message.size == null || message.size < 0
        ) {
            writeControl(FileControlMessage(type = "file-reject", transferId = message.transferId))
            return
        }

        // sanitizeRelativePath 已經濾掉 "."/".." 這種可能造成路徑穿越的片段,剩下的片段
        // 全部是我們自己組出來的乾淨字串,所以底下直接接在 DownloadStorage 的子目錄後面用即可。
        val relativePath = sanitizeRelativePath(message.relativePath, message.fileName)
        val lastSlash = relativePath.lastIndexOf('/')
        val fileName = if (lastSlash >= 0) relativePath.substring(lastSlash + 1) else relativePath
        val subDir = if (lastSlash < 0) "" else relativePath.substring(0, lastSlash)
        val combinedSubDir = when {
            baseSubDir.isEmpty() -> subDir
            subDir.isEmpty() -> baseSubDir
            else -> "$baseSubDir/$subDir"
        }

        val received = DownloadStorage.createFile(context, combinedSubDir, fileName, customDownloadFolderProvider())
        if (received == null) {
            onStatusChanged?.invoke("無法建立檔案。")
            writeControl(FileControlMessage(type = "folder-cancel", transferId = session.folderTransferId))
            abortActiveFolderSession(TransferEndReason.FAILED)
            return
        }

        val transfer = ActiveTransferState(
            transferId = message.transferId!!,
            direction = TransferDirection.RECEIVING,
            fileName = fileName,
            totalBytes = message.size,
            folderTransferId = session.folderTransferId,
            entryIndex = message.entryIndex,
        )
        transfer.outputStream = received.outputStream.buffered(RECEIVE_WRITE_BUFFER_SIZE)
        transfer.savedToken = received.token
        transfer.savedDisplayPath = received.displayPath
        activeTransfer = transfer

        writeControl(FileControlMessage(type = "file-accept", transferId = message.transferId))
    }

    private fun handleFolderAccept(message: FileControlMessage) {
        val session = activeFolderSession
        if (session == null || session.direction != TransferDirection.SENDING || session.folderTransferId != message.transferId) return
        sendNextFolderFileOrComplete(session)
    }

    private fun handleFolderReject(message: FileControlMessage) {
        val session = activeFolderSession
        if (session == null || session.folderTransferId != message.transferId) return
        activeFolderSession = null
        onFolderTransferEnded?.invoke(
            FolderTransferEnded(session.folderTransferId, session.direction, TransferEndReason.REJECTED, session.folderName, 0, session.isBatch)
        )
    }

    private fun handleFolderCancel(message: FileControlMessage) {
        val session = activeFolderSession
        if (session == null || session.folderTransferId != message.transferId) return
        activeFolderSession = null

        val currentEntry = activeTransfer
        if (currentEntry != null && currentEntry.folderTransferId == session.folderTransferId) {
            activeTransfer = null
            cleanupTransferResources(currentEntry)
        }

        onFolderTransferEnded?.invoke(
            FolderTransferEnded(session.folderTransferId, session.direction, TransferEndReason.CANCELLED_BY_REMOTE, session.folderName, session.completedEntries, session.isBatch)
        )
    }

    /** 顯示用的接收根目錄名稱:有自訂 SAF 資料夾就用它的名稱,否則用預設的 Download/ConnectIt。 */
    private fun downloadRootDisplayName(): String =
        customDownloadFolderProvider()?.let { uri -> runCatching { SafUtils.displayNameOf(context, uri) }.getOrNull() }
            ?: DownloadStorage.DISPLAY_ROOT

    private fun handleFolderComplete(message: FileControlMessage) {
        val session = activeFolderSession
        if (session == null || session.direction != TransferDirection.RECEIVING || session.folderTransferId != message.transferId) return
        activeFolderSession = null
        val displayPath = downloadRootDisplayName() + if (session.relativeSubDir.isNullOrEmpty()) "" else "/${session.relativeSubDir}"
        onFolderTransferEnded?.invoke(
            FolderTransferEnded(
                session.folderTransferId, TransferDirection.RECEIVING, TransferEndReason.COMPLETED,
                session.folderName, session.totalEntries, session.isBatch, displayPath,
            )
        )
    }

    private fun handleFileAccept(message: FileControlMessage) {
        val transfer = activeTransfer
        if (transfer == null || transfer.direction != TransferDirection.SENDING || transfer.transferId != message.transferId) return
        sendFileChunksAsync(transfer)
    }

    private suspend fun handleFileReject(message: FileControlMessage) {
        val transfer = activeTransfer
        if (transfer == null || transfer.transferId != message.transferId) return
        activeTransfer = null

        val folderTransferId = transfer.folderTransferId
        if (folderTransferId != null) {
            writeControl(FileControlMessage(type = "folder-cancel", transferId = folderTransferId))
            abortActiveFolderSession(TransferEndReason.FAILED)
            return
        }

        onFileTransferEnded?.invoke(FileTransferEnded(transfer.transferId, transfer.direction, TransferEndReason.REJECTED, transfer.fileName))
    }

    private fun handleFileCancel(message: FileControlMessage) {
        val transfer = activeTransfer
        if (transfer == null || transfer.transferId != message.transferId) return
        activeTransfer = null
        cleanupTransferResources(transfer)

        if (transfer.folderTransferId != null) {
            abortActiveFolderSession(TransferEndReason.CANCELLED_BY_REMOTE)
            return
        }

        onFileTransferEnded?.invoke(FileTransferEnded(transfer.transferId, transfer.direction, TransferEndReason.CANCELLED_BY_REMOTE, transfer.fileName))
    }

    private fun handleFileCompleteAck(message: FileControlMessage) {
        val transfer = activeTransfer
        if (transfer == null || transfer.direction != TransferDirection.SENDING || transfer.transferId != message.transferId) return
        activeTransfer = null

        val folderTransferId = transfer.folderTransferId
        val session = activeFolderSession
        if (folderTransferId != null && session != null && session.folderTransferId == folderTransferId) {
            session.completedBytes += transfer.totalBytes
            session.completedEntries += 1
            sendNextFolderFileOrComplete(session)
            return
        }

        onFileTransferEnded?.invoke(FileTransferEnded(transfer.transferId, TransferDirection.SENDING, TransferEndReason.COMPLETED, transfer.fileName))
    }

    private suspend fun handleChunkFrame(payload: ByteArray) {
        val transfer = activeTransfer
        if (transfer == null || transfer.direction != TransferDirection.RECEIVING || transfer.outputStream == null) {
            return // 已被取消/拒絕之後仍在路上的區塊,直接丟棄。
        }

        if (transfer.transferredBytes + payload.size > transfer.totalBytes) {
            failActiveTransfer(transfer, "收到超過預期大小的檔案內容。")
            return
        }

        try {
            transfer.outputStream!!.write(payload)
        } catch (e: Exception) {
            failActiveTransfer(transfer, "寫入檔案失敗:${e.message}")
            return
        }

        transfer.transferredBytes += payload.size
        reportProgress(transfer)

        if (transfer.transferredBytes == transfer.totalBytes) {
            // outputStream 有包一層緩衝,最後一段資料是在 close() 時才真正寫進磁碟——這裡的失敗
            // (例如空間不足)不能吞掉,否則會把沒寫完的檔案回報成接收成功。
            try {
                transfer.outputStream?.close()
            } catch (e: Exception) {
                failActiveTransfer(transfer, "寫入檔案失敗:${e.message}")
                return
            }
            activeTransfer = null
            transfer.savedToken?.let { DownloadStorage.markComplete(contentResolver, it) }
            writeControl(FileControlMessage(type = "file-complete", transferId = transfer.transferId))

            val folderTransferId = transfer.folderTransferId
            val session = activeFolderSession
            if (folderTransferId != null && session != null && session.folderTransferId == folderTransferId) {
                session.completedBytes += transfer.totalBytes
                session.completedEntries += 1
                return // 資料夾層級的完成事件要等收到傳送端送來的 folder-complete 才觸發。
            }

            onFileTransferEnded?.invoke(
                FileTransferEnded(transfer.transferId, TransferDirection.RECEIVING, TransferEndReason.COMPLETED, transfer.fileName, transfer.savedDisplayPath)
            )
        }
    }

    private suspend fun failActiveTransfer(transfer: ActiveTransferState, reason: String) {
        if (activeTransfer !== transfer) return // 已經被別的路徑(取消/斷線)結束了。

        activeTransfer = null
        cleanupTransferResources(transfer)
        onStatusChanged?.invoke(reason)

        val folderTransferId = transfer.folderTransferId
        if (folderTransferId != null) {
            writeControl(FileControlMessage(type = "folder-cancel", transferId = folderTransferId))
            abortActiveFolderSession(TransferEndReason.FAILED)
            return
        }

        writeControl(FileControlMessage(type = "file-cancel", transferId = transfer.transferId))
        onFileTransferEnded?.invoke(FileTransferEnded(transfer.transferId, transfer.direction, TransferEndReason.FAILED, transfer.fileName))
    }

    private fun abortActiveTransfer(reason: TransferEndReason) {
        val transfer = activeTransfer ?: return
        activeTransfer = null
        cleanupTransferResources(transfer)

        if (transfer.folderTransferId != null) return // 資料夾層級的結束事件由 abortActiveFolderSession 統一觸發。

        onFileTransferEnded?.invoke(FileTransferEnded(transfer.transferId, transfer.direction, reason, transfer.fileName))
    }

    private fun abortActiveFolderSession(reason: TransferEndReason) {
        val session = activeFolderSession ?: return
        activeFolderSession = null
        onFolderTransferEnded?.invoke(
            FolderTransferEnded(session.folderTransferId, session.direction, reason, session.folderName, session.completedEntries, session.isBatch)
        )
    }

    private fun reportProgress(transfer: ActiveTransferState) {
        // 每個 64KB 區塊都會呼叫到這裡,高速傳輸時一秒上千次;每次都組字串、更新 StateFlow、
        // 觸發 Compose 重組完全是浪費,限制成固定間隔回報一次(最後一塊一定回報,讓進度條走到底)。
        val now = SystemClock.elapsedRealtime()
        if (transfer.transferredBytes < transfer.totalBytes && now - transfer.lastProgressReportAt < PROGRESS_REPORT_INTERVAL_MS) {
            return
        }
        transfer.lastProgressReportAt = now

        val session = activeFolderSession
        var folderBytes: Long? = null
        var folderTotal: Long? = null
        if (transfer.folderTransferId != null && session != null && session.folderTransferId == transfer.folderTransferId) {
            folderBytes = session.completedBytes + transfer.transferredBytes
            folderTotal = session.totalBytes
        }

        onFileTransferProgress?.invoke(
            TransferProgress(
                transferId = transfer.transferId,
                direction = transfer.direction,
                bytesTransferred = transfer.transferredBytes,
                totalBytes = transfer.totalBytes,
                folderTransferId = transfer.folderTransferId,
                fileName = if (transfer.folderTransferId != null) transfer.fileName else null,
                entryIndex = transfer.entryIndex,
                totalEntries = session?.totalEntries,
                folderBytesTransferred = folderBytes,
                folderTotalBytes = folderTotal,
            )
        )
    }

    private fun cleanupTransferResources(transfer: ActiveTransferState) {
        transfer.job?.cancel()
        if (transfer.direction != TransferDirection.RECEIVING) return

        runCatching { transfer.outputStream?.close() }
        transfer.savedToken?.let { DownloadStorage.delete(contentResolver, it) }
    }

    fun dispose() {
        disconnect()
        stopListening()
    }

    private class ActiveTransferState(
        val transferId: String,
        val direction: TransferDirection,
        val fileName: String,
        val totalBytes: Long,
        val sourceUri: Uri? = null,
        val folderTransferId: String? = null,
        val entryIndex: Int? = null,
    ) {
        var transferredBytes: Long = 0L
        var lastProgressReportAt: Long = 0L
        var outputStream: OutputStream? = null

        /** 接收端:[DownloadStorage.createFile] 回傳的 token(Uri 或 File),失敗/取消時要靠這個刪檔。 */
        var savedToken: Any? = null
        var savedDisplayPath: String? = null
        var job: Job? = null
    }

    private class FolderSessionState(
        val folderTransferId: String,
        val direction: TransferDirection,
        val folderName: String,
        val totalBytes: Long,
        val totalEntries: Int,
        val isBatch: Boolean,
        val pendingFiles: ArrayDeque<FolderEntry>? = null,
    ) {
        var completedBytes: Long = 0L
        var completedEntries: Int = 0

        /** 接收端:Download/ConnectIt 底下的子目錄名稱,批次傳送是空字串(不建立子目錄)。 */
        var relativeSubDir: String? = null
    }

    companion object {
        private val INVALID_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        private fun sanitizeFileName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
            if (base.isBlank() || base == "." || base == ".." || base.any { it in INVALID_FILENAME_CHARS }) {
                return "file"
            }
            return base
        }

        private fun sanitizeRelativePath(relativePath: String?, fallbackFileName: String): String {
            val fileName = sanitizeFileName(fallbackFileName)
            if (relativePath.isNullOrBlank()) return fileName

            val segments = relativePath.replace('\\', '/').split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .map { segment -> if (segment.any { it in INVALID_FILENAME_CHARS }) "_" else segment }
                .toMutableList()

            if (segments.isEmpty()) return fileName

            segments[segments.size - 1] = fileName
            return segments.joinToString("/")
        }

        private fun randomId(): String = UUID.randomUUID().toString().replace("-", "")

        private fun Socket.closeQuietly() = runCatching { close() }
    }
}
