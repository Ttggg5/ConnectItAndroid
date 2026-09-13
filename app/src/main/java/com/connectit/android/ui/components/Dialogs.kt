package com.connectit.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.connectit.android.model.ConnectionRequest

/** [onRespond] 的第二個參數:是否同時把這台裝置加入信任清單(僅在接受時有意義)。 */
@Composable
fun ConnectionRequestDialog(request: ConnectionRequest, onRespond: (accept: Boolean, trust: Boolean) -> Unit) {
    var trust by remember(request) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onRespond(false, false) },
        title = { Text("「${request.requesterName}」想要與你連線") },
        text = {
            Column {
                Text(request.requesterAddress)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trust, onCheckedChange = { trust = it })
                    Text("信任此裝置,之後自動接受")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRespond(true, trust) }) { Text("接受") } },
        dismissButton = { TextButton(onClick = { onRespond(false, false) }) { Text("拒絕") } },
    )
}
