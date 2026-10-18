package com.connectit.android.ui.tv

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.repo.RecentlyWatched
import com.connectit.android.repo.RecentlyWatchedStore
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.VideoHostUiState
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.components.QrCode
import com.connectit.android.ui.components.formatDuration
import com.connectit.android.ui.theme.BrandColors

/** TV 版「影片」頁:拆成「觀看」與「分享」兩個分頁(對應手機版 VideoScreen)。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvVideoScreen(
    service: ConnectItService,
    onWatch: (DiscoveredDevice) -> Unit,
    onOpenHostControl: () -> Unit,
    onResume: (DiscoveredDevice, RecentlyWatched) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val hostState by service.videoHostState.collectAsState()

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvPageTitle(
                title = "影片",
                subtitle = "觀看區網內其他裝置分享的影片,或把這台電視的影片分享出去。",
                modifier = Modifier.weight(1f),
            )
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, leadingIcon = {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            }) { Text("觀看") }
            Spacer(Modifier.width(12.dp))
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, leadingIcon = {
                Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, modifier = Modifier.size(18.dp))
            }) { Text(if (hostState is VideoHostUiState.Running) "分享中" else "分享") }
        }
        Spacer(Modifier.height(16.dp))

        if (tab == 0) {
            TvWatchTab(service, onWatch, onResume)
        } else {
            TvShareTab(service, hostState, onOpenHostControl)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvWatchTab(
    service: ConnectItService,
    onWatch: (DiscoveredDevice) -> Unit,
    onResume: (DiscoveredDevice, RecentlyWatched) -> Unit,
) {
    val context = LocalContext.current
    val servers by service.videoServers.collectAsState()
    val store = remember { RecentlyWatchedStore.get(context) }
    val recent by store.entries.collectAsState()
    val resumable = recent.mapNotNull { entry ->
        servers.firstOrNull { it.displayName == entry.serverName }?.let { server -> server to entry }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (resumable.isNotEmpty()) {
            item { Text("繼續觀看", style = MaterialTheme.typography.titleLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(resumable, key = { (_, e) -> "${e.serverName}/${e.relativePath}" }) { (server, entry) ->
                        Card(
                            onClick = { onResume(server, entry) },
                            modifier = Modifier.width(320.dp),
                            border = tvBrandCardBorder(),
                            scale = tvBrandCardScale(),
                            colors = CardDefaults.colors(containerColor = Color.Transparent),
                        ) {
                            Box(Modifier.background(BrandColors.HeroDark).padding(20.dp)) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.History, contentDescription = null, tint = Color.White)
                                        Spacer(Modifier.width(10.dp))
                                        Text(entry.videoName, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "在 ${entry.serverName}・看到 ${formatDuration(entry.positionMs)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.8f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Text("區網內的影片伺服器", style = MaterialTheme.typography.titleLarge) }

        if (servers.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    TvEmptyState(
                        icon = Icons.Filled.VideoLibrary,
                        title = "正在尋找區網內的影片伺服器…",
                        description = "其他裝置在 ConnectIt 的「分享」分頁開始分享影片後,就會自動出現在這裡。",
                    )
                }
            }
        } else {
            items(servers, key = { it.key }) { server ->
                Card(
                    onClick = { onWatch(server) },
                    modifier = Modifier.fillMaxWidth(),
                    border = tvBrandCardBorder(),
                    scale = tvBrandCardScale(),
                    colors = CardDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GradientAvatar(icon = Icons.Filled.VideoLibrary, size = 52.dp, shape = RoundedCornerShape(16.dp))
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.displayName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${server.host}:${server.port}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("按 OK 觀看", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvShareTab(service: ConnectItService, hostState: VideoHostUiState, onOpenHostControl: () -> Unit) {
    val context = LocalContext.current
    val pickShareFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        service.startVideoServer(treeUri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GradientAvatar(icon = Icons.Filled.SettingsInputAntenna, size = 56.dp, shape = RoundedCornerShape(18.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("影片伺服器", style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    when (hostState) {
                                        is VideoHostUiState.Running -> "分享中:${hostState.folderName}・${hostState.videoCount} 部影片"
                                        VideoHostUiState.Idle -> "尚未開啟"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (hostState is VideoHostUiState.Running) BrandColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        when (hostState) {
                            is VideoHostUiState.Running -> {
                                hostState.url?.let { url ->
                                    Text(
                                        "其他裝置用瀏覽器打開這個網址(或用手機掃描右邊的 QR Code)就能觀看:",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(url, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Button(onClick = onOpenHostControl) {
                                        Icon(Icons.Filled.SettingsRemote, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("遙控模式")
                                    }
                                    OutlinedButton(onClick = { service.stopVideoServer() }) {
                                        Icon(Icons.Filled.Stop, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("停止分享")
                                    }
                                }
                            }
                            VideoHostUiState.Idle -> {
                                Text(
                                    "選擇一個影片資料夾,這台電視就會變成區網內的迷你影片網站,其他裝置用 ConnectIt 或瀏覽器就能觀看。",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = { pickShareFolder.launch(null) }) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("選擇資料夾開始分享")
                                }
                            }
                        }
                    }
                    val url = (hostState as? VideoHostUiState.Running)?.url
                    if (url != null) {
                        Spacer(Modifier.width(28.dp))
                        // 電視螢幕大,QR Code 放大一點,坐在沙發上用手機也掃得到。
                        QrCode(text = url, size = 220.dp, modifier = Modifier.clip(RoundedCornerShape(20.dp)))
                    }
                }
            }
        }
    }
}
