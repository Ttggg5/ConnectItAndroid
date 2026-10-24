package com.connectit.android.model

enum class TransferDirection { SENDING, RECEIVING }

enum class TransferEndReason { COMPLETED, REJECTED, CANCELLED, CANCELLED_BY_REMOTE, CONNECTION_CLOSED, FAILED }

/** 主動送出連線請求的結果,UI 依此顯示對應的說明(對應 Windows 端 ConnectionRequestOutcome)。 */
enum class ConnectionRequestOutcome {
    /** 對方按下接受,連線已建立。 */
    ACCEPTED,
    /** 對方按下拒絕(或直接關閉了連線)。 */
    REJECTED,
    /** 已送出請求,但對方在時限內沒有回應。 */
    TIMEOUT,
    /** 根本連不上對方(離線、位址不可達、被防火牆擋下)。 */
    UNREACHABLE,
}

/**
 * 這次連線期間的一筆傳輸紀錄,顯示在「目前連線」卡片的傳輸紀錄清單;接收成功、而且有 [savedUri] 的
 * 項目可以直接開啟。只存在記憶體裡,斷線後清空(對應 Windows 端 TransferRecord)。
 */
data class TransferRecord(
    val direction: TransferDirection,
    val reason: TransferEndReason,
    val name: String,
    val isFolder: Boolean,
    val savedUri: String? = null,
    /** 傳給誰/從誰收到(可以同時連線多台,紀錄裡要分得出是哪一台)。 */
    val peerName: String = "",
    val timeMillis: Long = System.currentTimeMillis(),
) {
    val isReceived: Boolean get() = direction == TransferDirection.RECEIVING
    val succeeded: Boolean get() = reason == TransferEndReason.COMPLETED

    /** 接收成功的檔案可以直接開啟;資料夾/多檔案則提供「開啟下載資料夾」。 */
    val canOpen: Boolean get() = isReceived && succeeded && (isFolder || savedUri != null)

    val statusText: String
        get() = when (reason) {
            TransferEndReason.COMPLETED -> if (isReceived) "已接收" else "已傳送"
            TransferEndReason.REJECTED -> "對方拒絕"
            TransferEndReason.CANCELLED -> "已取消"
            TransferEndReason.CANCELLED_BY_REMOTE -> "對方取消"
            TransferEndReason.CONNECTION_CLOSED -> "連線中斷"
            TransferEndReason.FAILED -> "失敗"
        }
}

/** 對方送出連線請求,等待使用者按下接受/拒絕。 */
data class ConnectionRequest(
    val requesterName: String,
    val requesterAddress: String,
    /** 對方的穩定裝置識別碼(舊版沒有時為 null)。 */
    val requesterDeviceId: String?,
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
    /** 接收成功時檔案的 content:// Uri 字串(MediaStore 或 SAF 文件),供「開啟檔案」使用;
     * 舊版 Android(API 28 以下)直接寫檔案路徑的情況為 null。 */
    val savedUri: String? = null,
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
    /** 這條連線在本機的識別碼(見 [com.connectit.android.connection.PeerConnection.peerId])。 */
    val peerId: String,
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
