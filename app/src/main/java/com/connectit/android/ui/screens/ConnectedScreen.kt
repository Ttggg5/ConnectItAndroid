package com.connectit.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.connectit.android.model.ConnectedPeer
import com.connectit.android.service.ConnectItService
import com.connectit.android.ui.components.AdaptiveContentWidth
import com.connectit.android.util.SafUtils

@Composable
fun ConnectedScreen(service: ConnectItService, peer: ConnectedPeer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val transferState by service.transferState.collectAsState()

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val files = uris.map { uri ->
            val (name, size) = SafUtils.queryNameAndSize(context.contentResolver, uri)
            Triple(uri, name, size)
        }
        service.sendFiles(files)
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val entries = SafUtils.enumerateTree(context, treeUri)
        service.sendFolder(SafUtils.displayNameOf(context, treeUri), entries)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(peer.name) }) },
    ) { padding ->
        AdaptiveContentWidth(modifier = Modifier.padding(padding)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("已連線裝置:${peer.name}(${peer.address})")

            val transferActive = service.isFileTransferActive
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = !transferActive) {
                    Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
                    Text(" 傳送檔案")
                }
                Button(onClick = { pickFolder.launch(null) }, enabled = !transferActive) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Text(" 傳送資料夾")
                }
            }

            transferState?.let { state ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text(state.statusText)
                        OutlinedButton(onClick = { service.cancelTransfer() }) { Text("取消") }
                    }
                }
            }

            OutlinedButton(onClick = { service.disconnectFromPeer() }) {
                Icon(Icons.Filled.LinkOff, contentDescription = null)
                Text(" 中斷連線")
            }
        }
        }
    }
}
