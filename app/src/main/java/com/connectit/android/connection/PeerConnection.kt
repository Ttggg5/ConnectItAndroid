package com.connectit.android.connection

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.connectit.android.model.FileOffer
import com.connectit.android.model.FileTransferEnded
import com.connectit.android.model.FolderEntry
import com.connectit.android.model.FolderOffer
import com.connectit.android.model.FolderTransferEnded
import com.connectit.android.model.TransferDirection
import com.connectit.android.model.TransferEndReason
import com.connectit.android.model.TransferProgress
import com.connectit.android.net.FILE_CHUNK_SIZE
import com.connectit.android.net.FileControlMessage
import com.connectit.android.net.FrameType
import com.connectit.android.net.ScreenMessage
import com.connectit.android.net.readFrame
import com.connectit.android.net.writeFrame
import com.connectit.android.util.DownloadStorage
import com.connectit.android.util.SafUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val PROGRESS_REPORT_INTERVAL_MS = 100L
private const val FRAME_HEADER_SIZE = 5
private const val SOCKET_READ_BUFFER_SIZE = 128 * 1024
// 接收端每收到一個 64KB 區塊就直接寫進 MediaStore/SAF 串流,在 Android 11+ 底下每次寫入都要
// 經過 FUSE,累積成較大的區塊再寫可以明顯減少這部分的開銷。
private const val RECEIVE_WRITE_BUFFER_SIZE = 1024 * 1024

/**
 * 一條已經完成交握的配對連線(對方一台裝置),對應 Windows 端 PeerSession.cs。
 * [ConnectionEngine] 負責監聽與交握,交握成功後為每台裝置建立一個 PeerConnection,所以可以同時連線
 * 多台裝置、各自獨立傳輸。這條連線上的訊框協定、心跳、檔案/資料夾傳輸狀態機與螢幕鏡像訊框都在這裡;
 * 同一條連線同一時間只允許一個進行中的傳輸,不同裝置之間互不影響。
 */
class PeerConnection internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val socket: Socket,
    /** 對方的顯示名稱。 */
    val name: String,
    /** 對方的位址(IPv4 字串)。 */
    val address: String,
    /** 對方在交握時帶上的穩定裝置識別碼(舊版沒有時為 null)。 */
    val remoteDeviceId: String?,
    /** 對方 TLS 憑證的指紋(見 [DeviceIdentity.fingerprint]),信任裝置靠這個比對。 */
    val remoteFingerprint: String,
    /** 這條連線的 6 位數配對碼(見 [DeviceIdentity.pairingCode])。 */
    val pairingCode: String,
    private val customDownloadFolderProvider: () -> Uri?,
) {
    private val contentResolver: ContentResolver get() = context.contentResolver

    /** 這條連線在本機的識別碼(每次連線都不同),畫面用來指定要對哪台裝置操作。 */
    val peerId: String = randomId()

    var onStatusChanged: ((String) -> Unit)? = null
    var onFileOffered: ((FileOffer) -> Unit)? = null
    var onFolderOffered: ((FolderOffer) -> Unit)? = null
    var onFileTransferProgress: ((TransferProgress) -> Unit)? = null
    var onFileTransferEnded: ((FileTransferEnded) -> Unit)? = null
    var onFolderTransferEnded: ((FolderTransferEnded) -> Unit)? = null
    var onScreenMessage: ((ScreenMessage) -> Unit)? = null
    var onScreenFrame: ((ByteArray) -> Unit)? = null
    var onScreenAudio: ((ByteArray) -> Unit)? = null

    /** 連線結束(對方斷線、心跳逾時,或本機呼叫 [disconnect]),參數為 true 代表不是本機主動中斷的。 */
    var onClosed: ((Boolean) -> Unit)? = null

    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    private var monitorJob: Job? = null

    // 心跳(對應 Windows 端 PeerSession):連線期間每隔 [HEARTBEAT_INTERVAL_MS] 送一個 "ping";
    // 確認對方也會送心跳之後,超過 [HEARTBEAT_TIMEOUT_MS] 完全沒收到對方任何訊框就判定連線已斷。
    // 用來偵測對方沒有正常關閉連線就消失(App 被強制關閉、手機斷網、電腦直接關機)——這時沒有 TCP FIN,
    // 讀取會一直卡住。只在對方也送心跳時才啟用逾時,舊版(不送心跳)的對方不會因為閒置被誤判斷線。
    private var heartbeatJob: Job? = null
    @Volatile private var lastReceivedAtMs = 0L
    @Volatile private var peerSendsHeartbeat = false
    @Volatile private var heartbeatWriteInFlight = false
    private val writeMutex = Mutex()

    /** 組裝訊框用的共用緩衝區,只在持有 [writeMutex] 時使用(見 [writeRaw])。 */
    private val frameScratch = ByteArray(FRAME_HEADER_SIZE + FILE_CHUNK_SIZE)

    @Volatile private var activeTransfer: ActiveTransferState? = null
    @Volatile private var activeFolderSession: FolderSessionState? = null

    val isFileTransferActive: Boolean get() = activeTransfer != null || activeFolderSession != null

    /** 這條連線上是不是有一個等待回應、TransferId 相符的檔案或資料夾提議(供 [ConnectionEngine] 依 TransferId 找連線)。 */
    internal fun hasPendingOffer(transferId: String): Boolean {
        val transfer = activeTransfer
        if (transfer != null && transfer.direction == TransferDirection.RECEIVING && transfer.outputStream == null && transfer.transferId == transferId) {
            return true
        }
        val session = activeFolderSession
        return session != null && session.direction == TransferDirection.RECEIVING && session.relativeSubDir == null && session.folderTransferId == transferId
    }

    internal fun start() {
        // 避免 TCP Nagle 演算法延遲小封包送出——與對方的 delayed ACK 交互作用下會嚴重拖慢傳輸速度。
        runCatching { socket.tcpNoDelay = true }
        startHeartbeat(socket)
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
                    lastReceivedAtMs = SystemClock.elapsedRealtime()
                    when (frame.type) {
                        FrameType.CONTROL -> handleControlFrame(frame.payload)
                        FrameType.CHUNK -> handleChunkFrame(frame.payload)
                        FrameType.SCREEN_CONTROL -> ScreenMessage.parse(frame.payload)?.let { onScreenMessage?.invoke(it) }
                        FrameType.SCREEN_FRAME -> onScreenFrame?.invoke(frame.payload)
                        FrameType.SCREEN_AUDIO -> onScreenAudio?.invoke(frame.payload)
                    }
                }
            } catch (e: CancellationException) {
                // 本機呼叫 disconnect() 取消的,不算對方斷線;下面照樣要清理並通知連線結束。
            } catch (e: Exception) {
                if (isActive) remoteClosed = true
            }

            heartbeatJob?.cancel()
            abortActiveTransfer(TransferEndReason.CONNECTION_CLOSED)
            abortActiveFolderSession(TransferEndReason.CONNECTION_CLOSED)

            // 讀取迴圈因例外結束時 socket 可能還開著,一定要關掉,對方才會偵測到斷線,
            // 而不是卡在等一個永遠不會來的回應(例如等 file-accept)。
            socket.closeQuietly()
            if (closed.compareAndSet(false, true)) {
                if (remoteClosed) onStatusChanged?.invoke("$name 已中斷連線。")
                onClosed?.invoke(remoteClosed)
            }
        }
    }

    /** 中斷這條連線;進行中的傳輸會被結束,並呼叫 [onClosed](參數為 false)。 */
    fun disconnect() {
        heartbeatJob?.cancel()
        monitorJob?.cancel()
        // 阻塞中的讀取不理會 coroutine 取消,關掉 socket 讓它丟例外結束,監控迴圈才會走到清理。
        socket.closeQuietly()
    }

    private fun startHeartbeat(socket: Socket) {
        heartbeatJob?.cancel()
        peerSendsHeartbeat = false
        lastReceivedAtMs = SystemClock.elapsedRealtime()
        // 作業系統層級的保險:對方是不送心跳的舊版本時,TCP keepalive 至少能在很久之後發現斷線。
        runCatching { socket.keepAlive = true }

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val silentMs = SystemClock.elapsedRealtime() - lastReceivedAtMs
                if (peerSendsHeartbeat && silentMs > HEARTBEAT_TIMEOUT_MS) {
                    onStatusChanged?.invoke("超過 ${HEARTBEAT_TIMEOUT_MS / 1000} 秒沒有收到對方的回應,判定連線已中斷。")
                    // 關掉 socket 讓卡在讀取的監控迴圈丟出例外,走一般的「對方斷線」流程(清理傳輸、通知畫面)。
                    socket.closeQuietly()
                    return@launch
                }
                // 另外開一個 coroutine 送:對方已經不在時寫入可能卡住,不能擋住上面的逾時判斷。
                if (!heartbeatWriteInFlight) {
                    heartbeatWriteInFlight = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            writeControl(FileControlMessage(type = HEARTBEAT_TYPE))
                        } finally {
                            heartbeatWriteInFlight = false
                        }
                    }
                }
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
        if (isClosed) return
        writeMutex.withLock {
            try {
                writeFrame(socket.getOutputStream(), type, payload, 0, length, frameScratch)
            } catch (e: Exception) {
                // 寫入失敗通常代表連線已經斷了,交給監控迴圈去處理斷線通知即可。
            }
        }
    }

    suspend fun sendScreenMessage(message: ScreenMessage) {
        val payload = message.toJson()
        writeRaw(FrameType.SCREEN_CONTROL, payload, payload.size)
    }

    /** [payload] 前 4 bytes 必須已經填好 big-endian 的畫面序號,後面接 JPEG 內容,總長 [length]。 */
    suspend fun sendScreenFrame(payload: ByteArray, length: Int) {
        writeRaw(FrameType.SCREEN_FRAME, payload, length)
    }

    /** 送出一段螢幕鏡像的聲音(16-bit little-endian PCM)。 */
    suspend fun sendScreenAudio(pcm: ByteArray, length: Int) {
        writeRaw(FrameType.SCREEN_AUDIO, pcm, length)
    }

    private fun launchWrite(message: FileControlMessage) {
        scope.launch(Dispatchers.IO) { writeControl(message) }
    }

    // ===================== 檔案/資料夾傳輸:送出 =====================

    fun sendFile(uri: Uri, fileName: String, size: Long) {
        if (isClosed) {
            onStatusChanged?.invoke("連線已中斷,無法傳送檔案。")
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
        if (isClosed) {
            onStatusChanged?.invoke("連線已中斷,無法傳送檔案。")
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
        if (isClosed) {
            onStatusChanged?.invoke("連線已中斷,無法傳送資料夾。")
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

        scope.launch(Dispatchers.IO) { acceptIncomingFile(transfer) }
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
        if (message.type == HEARTBEAT_TYPE) {
            // 心跳不需要回應,收到代表對方支援心跳,之後才啟用逾時判斷。
            peerSendsHeartbeat = true
            return
        }
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

        acceptIncomingFile(transfer)
    }

    /**
     * 接收端:回 file-accept。0 byte 的檔案不會有任何 Chunk 訊框,也就不會走到 [handleChunkFrame]
     * 裡「收滿宣告大小」的完成判斷,所以接受後直接完成,否則雙方會一直互等
     * (傳送端等 file-complete、接收端等資料)。
     */
    private suspend fun acceptIncomingFile(transfer: ActiveTransferState) {
        writeControl(FileControlMessage(type = "file-accept", transferId = transfer.transferId))
        if (transfer.totalBytes == 0L && activeTransfer === transfer) {
            completeReceivedFile(transfer)
        }
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
            completeReceivedFile(transfer)
        }
    }

    private suspend fun completeReceivedFile(transfer: ActiveTransferState) {
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
            FileTransferEnded(
                transfer.transferId, TransferDirection.RECEIVING, TransferEndReason.COMPLETED, transfer.fileName,
                transfer.savedDisplayPath, savedUri = (transfer.savedToken as? android.net.Uri)?.toString(),
            )
        )
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
        private const val HEARTBEAT_TYPE = "ping"
        private const val HEARTBEAT_INTERVAL_MS = 4_000L
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L
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
    }
}

internal fun randomId(): String = UUID.randomUUID().toString().replace("-", "")

internal fun Socket.closeQuietly() = runCatching { close() }
