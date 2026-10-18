package com.connectit.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.repo.RecentlyWatched
import com.connectit.android.repo.RecentlyWatchedStore
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.VideoHostUiState
import com.connectit.android.ui.components.AdaptiveContentWidth
import com.connectit.android.ui.components.BrandCard
import com.connectit.android.ui.components.EmptyState
import com.connectit.android.ui.components.GhostButton
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.components.GradientButton
import com.connectit.android.ui.components.GradientProgressBar
import com.connectit.android.ui.components.HeroCard
import com.connectit.android.ui.components.IconTile
import com.connectit.android.ui.components.QrCode
import com.connectit.android.ui.components.RadarPulse
import com.connectit.android.ui.components.SectionHeader
import com.connectit.android.ui.components.StatusPill
import com.connectit.android.ui.components.formatDuration
import com.connectit.android.ui.theme.BrandColors

/**
 * 「影片」頁:拆成「觀看」(搜尋區網內的影片伺服器、繼續觀看)與「分享」(把這台裝置的影片分享出去、
 * 網址/QR Code、遙控模式入口)兩個分頁,每個分頁只做一件事。
 */
@Composable
fun VideoScreen(
    service: ConnectItService,
    modifier: Modifier = Modifier,
    onWatch: (DiscoveredDevice) -> Unit,
    onOpenHostControl: () -> Unit,
    onResume: (DiscoveredDevice, RecentlyWatched) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val hostState by service.videoHostState.collectAsState()

    AdaptiveContentWidth(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                Text("影片", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "觀看區網內其他裝置分享的影片,或把這台裝置的影片分享出去。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { SegmentedButtonDefaults.Icon(active = tab == 0) { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)) } },
                    ) { Text("觀看") }
                    SegmentedButton(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { SegmentedButtonDefaults.Icon(active = tab == 1) { Icon(Icons.Filled.SettingsInputAntenna, null, Modifier.size(18.dp)) } },
                    ) {
                        Text(if (hostState is VideoHostUiState.Running) "分享中" else "分享")
                    }
                }
            }

            if (tab == 0) {
                WatchTab(service = service, onWatch = onWatch, onResume = onResume)
            } else {
                ShareTab(service = service, hostState = hostState, onOpenHostControl = onOpenHostControl)
            }
        }
    }
}

@Composable
private fun WatchTab(
    service: ConnectItService,
    onWatch: (DiscoveredDevice) -> Unit,
    onResume: (DiscoveredDevice, RecentlyWatched) -> Unit,
) {
    val context = LocalContext.current
    val servers by service.videoServers.collectAsState()
    val store = remember { RecentlyWatchedStore.get(context) }
    val recent by store.entries.collectAsState()
    // 只顯示目前在線上的伺服器上看過的影片(伺服器用顯示名稱比對)。
    val resumable = recent.mapNotNull { entry ->
        servers.firstOrNull { it.displayName == entry.serverName }?.let { server -> server to entry }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (resumable.isNotEmpty()) {
            item(key = "resume-header") {
                SectionHeader(title = "繼續觀看", modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            item(key = "resume-row") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resumable, key = { (_, e) -> "${e.serverName}/${e.relativePath}" }) { (server, entry) ->
                        ContinueWatchingCard(
                            entry = entry,
                            onClick = { onResume(server, entry) },
                            onDismiss = { store.remove(entry.serverName, entry.relativePath) },
                        )
                    }
                }
            }
        }

        item(key = "servers-header") {
            SectionHeader(title = "區網內的影片伺服器", modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }

        if (servers.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "正在尋找區網內的影片伺服器…",
                        description = "其他裝置在 ConnectIt 的「分享」分頁開始分享影片後,就會自動出現在這裡。",
                    ) { RadarPulse(icon = Icons.Filled.VideoLibrary) }
                }
            }
        } else {
            items(servers, key = { it.key }) { server ->
                BrandCard(
                    onClick = { onWatch(server) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GradientAvatar(icon = Icons.Filled.VideoLibrary, shape = RoundedCornerShape(14.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${server.host}:${server.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GradientButton(
                            onClick = { onWatch(server) },
                            text = "觀看",
                            icon = Icons.Filled.PlayArrow,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(entry: RecentlyWatched, onClick: () -> Unit, onDismiss: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = Color.Transparent, modifier = Modifier.width(260.dp)) {
        HeroCard(contentPadding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.History, contentDescription = null) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.videoName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "在 ${entry.serverName}・看到 ${formatDuration(entry.positionMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "移除這筆紀錄", modifier = Modifier.size(16.dp))
                }
            }
            if (entry.progress > 0f) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(entry.progress)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareTab(service: ConnectItService, hostState: VideoHostUiState, onOpenHostControl: () -> Unit) {
    val context = LocalContext.current
    val pickShareFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        service.startVideoServer(treeUri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            BrandCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientAvatar(icon = Icons.Filled.SettingsInputAntenna, size = 52.dp, shape = RoundedCornerShape(16.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("影片伺服器", style = MaterialTheme.typography.titleLarge)
                        when (hostState) {
                            is VideoHostUiState.Running -> StatusPill(
                                text = "分享中:${hostState.folderName}・${hostState.videoCount} 部",
                                containerColor = BrandColors.Success.copy(alpha = 0.15f),
                                contentColor = BrandColors.Success,
                                dotColor = BrandColors.Success,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            VideoHostUiState.Idle -> Text(
                                "尚未開啟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                when (hostState) {
                    is VideoHostUiState.Running -> GhostButton(
                        onClick = { service.stopVideoServer() },
                        text = "停止分享",
                        icon = Icons.Filled.Stop,
                        danger = true,
                    )
                    VideoHostUiState.Idle -> {
                        Text(
                            "選擇一個影片資料夾,這台裝置就會變成區網內的迷你影片網站。不需要先配對連線,其他裝置用 ConnectIt 或瀏覽器就能觀看。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        GradientButton(
                            onClick = { pickShareFolder.launch(null) },
                            text = "選擇資料夾開始分享",
                            icon = Icons.Filled.FolderOpen,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        val running = hostState as? VideoHostUiState.Running
        if (running?.url != null) {
            item {
                BrandCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(icon = Icons.Filled.Language)
                        Spacer(Modifier.width(12.dp))
                        Text("用瀏覽器觀看", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "同一區網的手機、平板或電腦,用瀏覽器打開這個網址(或用相機掃描 QR Code)就能觀看,不需要安裝 App。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
                    )
                    QrCode(text = running.url, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                running.url,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { copyToClipboard(context, running.url) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "複製網址")
                            }
                        }
                    }
                }
            }
        }

        if (running != null) {
            item {
                BrandCard(onClick = onOpenHostControl, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(icon = Icons.Filled.SettingsRemote, size = 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("遙控模式", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "讓所有觀看端同步播放你選的影片,適合一起看片或簡報。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ConnectIt", text))
    Toast.makeText(context, "已複製網址", Toast.LENGTH_SHORT).show()
}
