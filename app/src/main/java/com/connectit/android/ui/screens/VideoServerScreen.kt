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
import androidx.compose.material.icons.filled.SettingsRemote
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
import com.connectit.android.net.fetchControlState
import com.connectit.android.net.fetchVideoManifest
import com.connectit.android.net.videoThumbnailUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private sealed interface ManifestState {
    data object Loading : ManifestState
    data object Failed : ManifestState
    data class Loaded(val response: VideoManifestResponse) : ManifestState

    /** 遠端控制模式已開啟,但主機還沒選片(輪詢確認過,不是還沒查詢)。跟 [Loaded] 分開,而不是
     * 用一個額外的 `remoteEnabled` 布林值疊加在 [Loaded] 上——這樣「主機是否已經選好片」的判斷
     * 一定是查過 `/control/state` 之後才會決定要進哪個畫面,不會有主機其實已經選好片、卻先閃一下
     * 「等待主機選片」畫面才跳走的情形(見 [VideoServerScreen] 裡怎麼組出這個狀態的說明)。 */
    data class RemoteWaiting(val response: VideoManifestResponse) : ManifestState
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
    onPlay: (entries: List<VideoManifestEntry>, startIndex: Int, remoteControlEnabled: Boolean) -> Unit,
) {
    var state by remember(server) { mutableStateOf<ManifestState>(ManifestState.Loading) }
    var sort by remember { mutableStateOf(VideoSortOption.NAME_ASC) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    // 遠端控制模式中不能讓觀眾自己瀏覽/挑影片(跟網站不該顯示首頁清單同一個道理)。這裡在拿到
    // manifest 後,若偵測到遠端控制已開啟,會「在畫面真的畫出來之前」就先查一次目前的播放狀態
    // ——如果主機在觀眾連上來之前就已經選好影片,這裡會直接跳進播放畫面,中間完全不會經過
    // 「等待主機選片」那個畫面,不會讓使用者誤以為卡住。真的沒選片時才會停在 [ManifestState.RemoteWaiting]。
    LaunchedEffect(server) {
        val response = fetchVideoManifest(server.host, server.port)
        if (response == null) {
            state = ManifestState.Failed
            return@LaunchedEffect
        }

        if (response.remoteControlEnabled) {
            val controlState = fetchControlState(server.host, server.port)
            val index = controlState
                ?.takeIf { it.enabled }
                ?.videoRelativePath
                ?.let { path -> response.entries.indexOfFirst { it.relativePath == path } }
                ?.takeIf { it >= 0 }
            if (index != null) {
                onPlay(response.entries, index, true)
                return@LaunchedEffect
            }
            state = ManifestState.RemoteWaiting(response)
        } else {
            state = ManifestState.Loaded(response)
        }
    }

    // 進畫面後持續輪詢,處理「稍後才發生」的狀態變化:主機在觀眾已經看著清單/等待畫面時才開啟
    // 遠端控制、選片、或是把遠端控制關掉——都靠這個迴圈即時反映,不用使用者自己重新整理。
    LaunchedEffect(server) {
        while (isActive) {
            delay(800)

            val response = when (val current = state) {
                is ManifestState.Loaded -> current.response
                is ManifestState.RemoteWaiting -> current.response
                ManifestState.Loading, ManifestState.Failed -> continue
            }

            val controlState = fetchControlState(server.host, server.port) ?: continue
            if (controlState.enabled) {
                val index = controlState.videoRelativePath
                    ?.let { path -> response.entries.indexOfFirst { it.relativePath == path } }
                    ?.takeIf { it >= 0 }
                if (index != null) {
                    onPlay(response.entries, index, true)
                    return@LaunchedEffect
                }
                if (state !is ManifestState.RemoteWaiting) {
                    state = ManifestState.RemoteWaiting(response)
                }
            } else if (state is ManifestState.RemoteWaiting) {
                state = ManifestState.Loaded(response)
            }
        }
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
            is ManifestState.RemoteWaiting -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.SettingsRemote,
                        contentDescription = null,
                        modifier = Modifier.padding(bottom = 12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("遙控模式已開啟,等待主機選擇影片…")
                }
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
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            // 只有在 ManifestState.Loaded 分支才會畫出這個可自由瀏覽的格線,遠端控制
                            // 模式一開啟就會轉去 RemoteWaiting/直接進播放畫面,不會停在這裡讓人選片,
                            // 所以這裡點下去一定是「自由觀看」,不用再帶遠端控制旗標。
                            onClick = { onPlay(entries, index, false) },
                        ) {
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
