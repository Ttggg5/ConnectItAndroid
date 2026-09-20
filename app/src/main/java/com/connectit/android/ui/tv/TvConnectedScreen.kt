package com.connectit.android.ui.tv

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.connectit.android.model.ConnectedPeer
import com.connectit.android.service.ConnectItService
import com.connectit.android.util.SafUtils

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvConnectedScreen(service: ConnectItService, peer: ConnectedPeer) {
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

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("已連線裝置:${peer.name}(${peer.address})", style = MaterialTheme.typography.headlineSmall)

        val transferActive = service.isFileTransferActive
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Surface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
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
