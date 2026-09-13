package com.connectit.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.model.VideoManifestResponse
import com.connectit.android.net.fetchVideoManifest
import com.connectit.android.net.videoThumbnailUrl

private sealed interface ManifestState {
    data object Loading : ManifestState
    data object Failed : ManifestState
    data class Loaded(val response: VideoManifestResponse) : ManifestState
}

/** 對應 Windows 端 VideoStreamingService.cs 的 SortOptions:(查詢字串值、下拉選單顯示文字)。 */
private enum class VideoSortOption(val label: String) {
    NAME_ASC("檔名(A→Z)"),
    NAME_DESC("檔名(Z→A)"),
    SIZE_ASC("檔案大小(小→大)"),
    SIZE_DESC("檔案大小(大→小)"),
    DATE_DESC("修改時間(新→舊)"),
    DATE_ASC("修改時間(舊→新)"),
}

private fun List<VideoManifestEntry>.sortedByOption(option: VideoSortOption): List<VideoManifestEntry> = when (option) {
    VideoSortOption.NAME_ASC -> sortedBy { it.name.lowercase() }
    VideoSortOption.NAME_DESC -> sortedByDescending { it.name.lowercase() }
    VideoSortOption.SIZE_ASC -> sortedBy { it.size }
    VideoSortOption.SIZE_DESC -> sortedByDescending { it.size }
    VideoSortOption.DATE_DESC -> sortedByDescending { it.modifiedEpochMillis ?: 0L }
    VideoSortOption.DATE_ASC -> sortedBy { it.modifiedEpochMillis ?: 0L }
}

@Composable
fun VideoServerScreen(
    server: DiscoveredDevice,
    onBack: () -> Unit,
    onPlay: (entries: List<VideoManifestEntry>, startIndex: Int) -> Unit,
) {
    var state by remember(server) { mutableStateOf<ManifestState>(ManifestState.Loading) }
    var sort by remember { mutableStateOf(VideoSortOption.NAME_ASC) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(server) {
        val response = fetchVideoManifest(server.host, server.port)
        state = if (response != null) ManifestState.Loaded(response) else ManifestState.Failed
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序方式")
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            VideoSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        sort = option
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            is ManifestState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ManifestState.Failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("無法取得影片清單,請確認對方裝置仍在線上。")
            }
            is ManifestState.Loaded -> {
                val entries = current.response.entries.sortedByOption(sort)
                // 用自動排列的格線,而不是固定單欄清單:折疊機攤開、平板橫向這種寬螢幕下會自動
                // 排成兩欄以上,對應 Windows 首頁 `repeat(auto-fill, minmax(220px,1fr))` 的效果,
                // 手機直向寬度不夠時自然退回單欄,行為不變。
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 200.dp),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(entries, key = { it.relativePath }) { entry ->
                        val index = entries.indexOf(entry)
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { onPlay(entries, index) }) {
                            Column {
                                Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                    AsyncImage(
                                        model = videoThumbnailUrl(server.host, server.port, entry.relativePath),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Icon(
                                        Icons.Filled.PlayCircle,
                                        contentDescription = "播放",
                                        modifier = Modifier.align(Alignment.Center),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                    Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
