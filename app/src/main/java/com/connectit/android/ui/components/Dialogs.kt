package com.connectit.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.connectit.android.model.ConnectionRequest
import com.connectit.android.model.FileOffer
import com.connectit.android.model.FolderOffer

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

@Composable
fun ConnectionRequestDialog(request: ConnectionRequest, onRespond: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onRespond(false) },
        title = { Text("「${request.requesterName}」想要與你連線") },
        text = { Text(request.requesterAddress) },
        confirmButton = { TextButton(onClick = { onRespond(true) }) { Text("接受") } },
        dismissButton = { TextButton(onClick = { onRespond(false) }) { Text("拒絕") } },
    )
}

@Composable
fun FileOfferDialog(offer: FileOffer, onRespond: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onRespond(false) },
        title = { Text("對方想要傳送檔案給你") },
        text = { Text("${offer.fileName}(${formatBytes(offer.fileSize)})") },
        confirmButton = { TextButton(onClick = { onRespond(true) }) { Text("接受") } },
        dismissButton = { TextButton(onClick = { onRespond(false) }) { Text("拒絕") } },
    )
}

@Composable
fun FolderOfferDialog(offer: FolderOffer, onRespond: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onRespond(false) },
        title = { Text(if (offer.isBatch) "對方想要傳送多個檔案給你" else "對方想要傳送資料夾給你") },
        text = {
            Text(
                if (offer.isBatch) {
                    "${offer.totalEntries} 個檔案,共 ${formatBytes(offer.totalSize)}"
                } else {
                    "${offer.folderName}(${offer.totalEntries} 個檔案,共 ${formatBytes(offer.totalSize)})"
                }
            )
        },
        confirmButton = { TextButton(onClick = { onRespond(true) }) { Text("接受") } },
        dismissButton = { TextButton(onClick = { onRespond(false) }) { Text("拒絕") } },
    )
}
