package com.connectit.android.model

enum class TransferDirection { SENDING, RECEIVING }

enum class TransferEndReason { COMPLETED, REJECTED, CANCELLED, CANCELLED_BY_REMOTE, CONNECTION_CLOSED, FAILED }

/** 對方送出連線請求,等待使用者按下接受/拒絕。 */
data class ConnectionRequest(
    val requesterName: String,
    val requesterAddress: String,
    val respond: (Boolean) -> Unit,
)

/** 對方想傳送單一檔案給我們,等待使用者確認。 */
data class FileOffer(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
)

/** 對方想傳送整個資料夾(或多個獨立檔案)給我們,等待使用者確認。 */
data class FolderOffer(
    val transferId: String,
    val folderName: String,
    val totalSize: Long,
    val totalEntries: Int,
    val isBatch: Boolean,
)

data class TransferProgress(
    val transferId: String,
    val direction: TransferDirection,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val folderTransferId: String?,
    val fileName: String?,
    val entryIndex: Int?,
    val totalEntries: Int?,
    val folderBytesTransferred: Long?,
    val folderTotalBytes: Long?,
)

data class FileTransferEnded(
    val transferId: String,
    val direction: TransferDirection,
    val reason: TransferEndReason,
    val fileName: String,
    val savedFilePath: String? = null,
)

data class FolderTransferEnded(
    val transferId: String,
    val direction: TransferDirection,
    val reason: TransferEndReason,
    val folderName: String,
    val entryCount: Int,
    val isBatch: Boolean,
    val savedFolderPath: String? = null,
)

/** 已連線的對象。 */
data class ConnectedPeer(
    val name: String,
    val address: String,
)

/** 使用者要傳送出去的項目:單一檔案的 uri/名稱/大小,或整個資料夾/多檔案批次。 */
sealed class OutgoingSelection {
    data class SingleFile(val uri: android.net.Uri, val name: String, val size: Long) : OutgoingSelection()
    data class MultipleFiles(val files: List<SingleFile>) : OutgoingSelection()
    data class Folder(val treeUri: android.net.Uri, val name: String, val entries: List<FolderEntry>) : OutgoingSelection()
}

/** 資料夾傳輸裡的一個檔案項目(來源 uri、相對路徑、大小)。 */
data class FolderEntry(val uri: android.net.Uri, val relativePath: String, val size: Long)
