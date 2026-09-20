package com.connectit.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.CircularProgressIndicator
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
import com.connectit.android.ui.screens.VideoSortOption
import com.connectit.android.ui.screens.sortedByOption
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.OutlinedButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private sealed interface TvManifestState {
    data object Loading : TvManifestState
    data object Failed : TvManifestState
    data class Loaded(val response: VideoManifestResponse) : TvManifestState
    data class RemoteWaiting(val response: VideoManifestResponse) : TvManifestState
}

/** 跟手機版 VideoServerScreen.kt 裡的私有版本邏輯完全一致(依相對路徑字首比對資料夾結構),
 * 只是那三個輔助函式是 private,無法直接匯入共用,這裡重新宣告一份。 */
private fun subfoldersOf(entries: List<VideoManifestEntry>, folder: String): List<String> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    return entries
        .mapNotNull { entry ->
            if (prefix.isNotEmpty() && !entry.relativePath.startsWith(prefix, ignoreCase = true)) return@mapNotNull null
            val remainder = entry.relativePath.substring(prefix.length)
            val slashIndex = remainder.indexOf('/')
            if (slashIndex < 0) null else remainder.substring(0, slashIndex)
        }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
}

private fun entriesDirectlyIn(entries: List<VideoManifestEntry>, folder: String): List<VideoManifestEntry> {
    val prefix = if (folder.isEmpty()) "" else "$folder/"
    return entries.filter { entry ->
        if (prefix.isNotEmpty() && !entry.relativePath.startsWith(prefix, ignoreCase = true)) return@filter false
        !entry.relativePath.substring(prefix.length).contains('/')
    }
}

private fun countVideosUnder(entries: List<VideoManifestEntry>, folderPath: String): Int {
    val prefix = "$folderPath/"
    return entries.count { it.relativePath.startsWith(prefix, ignoreCase = true) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvVideoServerScreen(
    server: DiscoveredDevice,
    onBack: () -> Unit,
    onPlay: (entries: List<VideoManifestEntry>, startIndex: Int, remoteControlEnabled: Boolean) -> Unit,
) {
    var state by remember(server) { mutableStateOf<TvManifestState>(TvManifestState.Loading) }
    val sort = VideoSortOption.NAME_ASC

    var currentFolder by remember(server) { mutableStateOf("") }

    BackHandler {
        if (currentFolder.isNotEmpty()) {
            currentFolder = currentFolder.substringBeforeLast('/', "")
        } else {
            onBack()
        }
    }

    LaunchedEffect(server) {
        val response = fetchVideoManifest(server.host, server.port)
        if (response == null) {
            state = TvManifestState.Failed
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
            state = TvManifestState.RemoteWaiting(response)
        } else {
            state = TvManifestState.Loaded(response)
        }
    }

    LaunchedEffect(server) {
        while (isActive) {
            delay(800)

            val response = when (val current = state) {
                is TvManifestState.Loaded -> current.response
                is TvManifestState.RemoteWaiting -> current.response
                TvManifestState.Loading, TvManifestState.Failed -> continue
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
                if (state !is TvManifestState.RemoteWaiting) {
                    state = TvManifestState.RemoteWaiting(response)
                }
            } else if (state is TvManifestState.RemoteWaiting) {
                state = TvManifestState.Loaded(response)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(server.displayName, style = MaterialTheme.typography.headlineSmall)
        }

        when (val current = state) {
            is TvManifestState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is TvManifestState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("無法取得影片清單,請確認對方裝置仍在線上。")
            }
            is TvManifestState.RemoteWaiting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.SettingsRemote,
                        contentDescription = null,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Text("遙控模式已開啟,等待主機選擇影片…")
                }
            }
            is TvManifestState.Loaded -> {
                val sortedEntries = current.response.entries.sortedByOption(sort)
                val subfolders = subfoldersOf(current.response.entries, currentFolder)
                val visibleEntries = entriesDirectlyIn(sortedEntries, currentFolder)

                if (currentFolder.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { currentFolder = "" }) { Text("首頁") }
                        var accumulated = ""
                        val segments = currentFolder.split('/')
                        segments.forEachIndexed { i, segment ->
                            Text("/", modifier = Modifier.padding(horizontal = 4.dp))
                            accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
                            val path = accumulated
                            if (i == segments.lastIndex) {
                                Text(segment, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                OutlinedButton(onClick = { currentFolder = path }) { Text(segment) }
                            }
                        }
                    }
                }

                if (subfolders.isEmpty() && visibleEntries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("這個資料夾是空的。")
                    }
                    return@Column
                }

                // TV 螢幕固定夠寬,用固定欄數的格線,10 呎外距離看起來卡片大小才一致、好瞄準遙控器焦點,
                // 不像手機版用 Adaptive 依實際可用寬度(含折疊機、平板)自動決定欄數。
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(24.dp),
                ) {
                    items(subfolders, key = { "folder:$it" }) { name ->
                        val childPath = if (currentFolder.isEmpty()) name else "$currentFolder/$name"
                        val count = countVideosUnder(current.response.entries, childPath)
                        Card(onClick = { currentFolder = childPath }, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(48.dp))
                                }
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                    Text("$count 部影片", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    items(visibleEntries, key = { it.relativePath }) { entry ->
                        val index = visibleEntries.indexOf(entry)
                        Card(onClick = { onPlay(visibleEntries, index, false) }, modifier = Modifier.fillMaxWidth()) {
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
