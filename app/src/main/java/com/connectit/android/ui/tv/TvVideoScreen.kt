package com.connectit.android.ui.tv

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.VideoHostUiState

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvVideoScreen(
    service: ConnectItService,
    onWatch: (DiscoveredDevice) -> Unit,
    onOpenHostControl: () -> Unit,
) {
    val context = LocalContext.current
    val servers by service.videoServers.collectAsState()
    val hostState by service.videoHostState.collectAsState()

    val pickShareFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        service.startVideoServer(treeUri)
    }

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("影片", style = MaterialTheme.typography.headlineSmall)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TvVideoHostCard(
                    hostState = hostState,
                    onStartSharing = { pickShareFolder.launch(null) },
                    onStopSharing = { service.stopVideoServer() },
                    onOpenHostControl = onOpenHostControl,
                )
            }

            if (servers.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("正在搜尋影片伺服器...", modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                }
            } else {
                items(servers, key = { it.key }) { server ->
                    Card(onClick = { onWatch(server) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(Icons.Filled.PlayCircle, contentDescription = "觀看")
                            Column {
                                Text(server.displayName, style = MaterialTheme.typography.titleMedium)
                                Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvVideoHostCard(
    hostState: VideoHostUiState,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit,
    onOpenHostControl: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("分享這台裝置的影片", style = MaterialTheme.typography.titleMedium)
            when (hostState) {
                is VideoHostUiState.Running -> {
                    Text("執行中:${hostState.folderName},共 ${hostState.videoCount} 部影片(連接埠 ${hostState.port})")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onStopSharing) { Text("停止分享") }
                        Button(onClick = onOpenHostControl) {
                            Icon(Icons.Filled.SettingsRemote, contentDescription = null)
                            Text(" 遙控模式")
                        }
                    }
                }
                VideoHostUiState.Idle -> {
                    Text("選擇一個資料夾,讓其他裝置能在區網內搜尋、觀看裡面的影片。")
                    Button(onClick = onStartSharing) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Text(" 選擇資料夾開始分享")
                    }
                }
            }
        }
    }
}
