package com.connectit.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.connectit.android.model.ConnectionRequest

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
