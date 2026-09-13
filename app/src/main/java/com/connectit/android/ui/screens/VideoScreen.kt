package com.connectit.android.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.VideoHostUiState
import com.connectit.android.ui.components.AdaptiveContentWidth

@Composable
fun VideoScreen(service: ConnectItService, modifier: Modifier = Modifier, onWatch: (DiscoveredDevice) -> Unit) {
    val context = LocalContext.current
    val servers by service.videoServers.collectAsState()
    val hostState by service.videoHostState.collectAsState()

    val pickShareFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        service.startVideoServer(treeUri)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("影片") }) },
    ) { padding ->
        AdaptiveContentWidth(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    VideoHostCard(
                        hostState = hostState,
                        onStartSharing = { pickShareFolder.launch(null) },
                        onStopSharing = { service.stopVideoServer() },
                    )
                }

                if (servers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text("正在搜尋影片伺服器...", modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                } else {
                    items(servers, key = { it.key }) { server ->
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { onWatch(server) }) {
                            ListItem(
                                headlineContent = { Text(server.displayName) },
                                supportingContent = { Text("${server.host}:${server.port}") },
                                leadingContent = { Icon(Icons.Filled.PlayCircle, contentDescription = "觀看") },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 把這台裝置的影片庫分享出去(見 [com.connectit.android.video.VideoHostServer]),讓其他裝置
 * 用跟這個畫面下方清單一樣的方式找到、觀看。 */
@Composable
private fun VideoHostCard(
    hostState: VideoHostUiState,
    onStartSharing: () -> Unit,
    onStopSharing: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("分享這台裝置的影片", style = MaterialTheme.typography.titleMedium)
            when (hostState) {
                is VideoHostUiState.Running -> {
                    Text("執行中:${hostState.folderName},共 ${hostState.videoCount} 部影片(連接埠 ${hostState.port})")
                    OutlinedButton(onClick = onStopSharing) { Text("停止分享") }
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
