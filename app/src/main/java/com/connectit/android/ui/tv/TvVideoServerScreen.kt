package com.connectit.android.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.model.VideoManifestResponse
import com.connectit.android.net.fetchControlState
import com.connectit.android.net.fetchVideoManifest
import com.connectit.android.net.observeControlState
import com.connectit.android.net.videoThumbnailUrl
import com.connectit.android.repo.PlaybackPreferences
import com.connectit.android.ui.components.VideoThumbnail
import com.connectit.android.ui.components.formatBytes
import com.connectit.android.ui.screens.VideoSortOption
import com.connectit.android.ui.screens.sortedByOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

/**
 * TV 版瀏覽影片伺服器的畫面,功能對齊手機版 VideoServerScreen:資料夾逐層瀏覽/攤平成單一清單、
 * 排序、麵包屑導覽;另外加上「播放全部」「隨機播放」(對應主機網頁版首頁的隨機播放按鈕),
 * 遙控器不用一部一部挑也能直接開播。下拉選單在遙控器上不好操作,排序改成按一下切換下一種。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvVideoServerScreen(
    server: DiscoveredDevice,
    onBack: () -> Unit,
    onPlay: (entries: List<VideoManifestEntry>, startIndex: Int, remoteControlEnabled: Boolean) -> Unit,
    /** 從「繼續觀看」進來時要直接播放的影片(相對路徑)。 */
    resumePath: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PlaybackPreferences(context.applicationContext) }

    var state by remember(server) { mutableStateOf<TvManifestState>(TvManifestState.Loading) }
    var sort by remember { mutableStateOf(VideoSortOption.NAME_ASC) }
    var currentFolder by remember(server) { mutableStateOf("") }
    var flatten by remember(server) { mutableStateOf(false) }
    // 返回上一層資料夾時,焦點要落回剛剛離開的那個資料夾卡片,而不是跳回第一格。
    var focusTargetKey by remember(server) { mutableStateOf<String?>(null) }
    val gridFocusRequester = remember { FocusRequester() }

    fun goToFolder(path: String) {
        focusTargetKey = null
        currentFolder = path
    }

    fun goUpOneLevel() {
        val leaving = currentFolder.substringAfterLast('/')
        currentFolder = currentFolder.substringBeforeLast('/', "")
        focusTargetKey = "folder:$leaving"
    }

    BackHandler {
        if (!flatten && currentFolder.isNotEmpty()) goUpOneLevel() else onBack()
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
            val resumeIndex = resumePath?.let { path ->
                response.entries.sortedByOption(sort).indexOfFirst { it.relativePath == path }
            }?.takeIf { it >= 0 }
            if (resumeIndex != null) {
                onPlay(response.entries.sortedByOption(sort), resumeIndex, false)
                return@LaunchedEffect
            }
            state = TvManifestState.Loaded(response)
        }
    }

    // 跟手機版 VideoServerScreen 一樣訂閱主機推送的狀態,跳進播放畫面後就停止訂閱。
    LaunchedEffect(server) {
        observeControlState(server.host, server.port).first { controlState ->
            val response = when (val current = state) {
                is TvManifestState.Loaded -> current.response
                is TvManifestState.RemoteWaiting -> current.response
                TvManifestState.Loading, TvManifestState.Failed -> return@first false
            }

            if (controlState.enabled) {
                val index = controlState.videoRelativePath
                    ?.let { path -> response.entries.indexOfFirst { it.relativePath == path } }
                    ?.takeIf { it >= 0 }
                if (index != null) {
                    onPlay(response.entries, index, true)
                    return@first true
                }
                if (state !is TvManifestState.RemoteWaiting) {
                    state = TvManifestState.RemoteWaiting(response)
                }
            } else if (state is TvManifestState.RemoteWaiting) {
                state = TvManifestState.Loaded(response)
            }
            false
        }
    }

    val loaded = state as? TvManifestState.Loaded
    val allEntries = loaded?.response?.entries.orEmpty()
    // 同手機版:用 remember 快取,不要每次重組(TV 上每次焦點移動都可能觸發)都重新排序/過濾。
    val sortedEntries = remember(allEntries, sort) { allEntries.sortedByOption(sort) }
    val subfolders = remember(allEntries, currentFolder, flatten) {
        if (flatten) emptyList() else subfoldersOf(allEntries, currentFolder)
    }
    val visibleEntries = remember(sortedEntries, currentFolder, flatten) {
        if (flatten) sortedEntries else entriesDirectlyIn(sortedEntries, currentFolder)
    }
    // 「播放全部/隨機播放」涵蓋目前這一層底下所有影片(含子資料夾),攤平時就是整份清單。
    val playAllEntries = remember(sortedEntries, currentFolder, flatten) {
        if (flatten || currentFolder.isEmpty()) {
            sortedEntries
        } else {
            sortedEntries.filter { it.relativePath.startsWith("$currentFolder/", ignoreCase = true) }
        }
    }

    fun startPlayAll(shuffled: Boolean) {
        if (playAllEntries.isEmpty()) return
        scope.launch {
            // 播放畫面一開始會讀這個設定決定播放順序,要先寫進去再切畫面。
            prefs.setShuffle(shuffled)
            onPlay(playAllEntries, if (shuffled) playAllEntries.indices.random() else 0, false)
        }
    }

    // 換資料夾/切換檢視/改排序之後,焦點跟著移到格線上(被點的卡片已經不在畫面上了,不處理的話
    // 焦點會掉到不知道哪裡去)。
    LaunchedEffect(loaded != null, currentFolder, flatten) {
        if (loaded == null) return@LaunchedEffect
        delay(50)
        runCatching { gridFocusRequester.requestFocus() }
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
            Column(Modifier.weight(1f)) {
                Text(server.displayName, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append("${server.host}:${server.port}")
                        if (loaded != null) append("・共 ${allEntries.size} 部影片")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loaded != null && allEntries.isNotEmpty()) {
                OutlinedButton(onClick = { startPlayAll(shuffled = false) }, enabled = playAllEntries.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                    Text(" 播放全部")
                }
                OutlinedButton(onClick = { startPlayAll(shuffled = true) }, enabled = playAllEntries.isNotEmpty()) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text(" 隨機播放")
                }
                OutlinedButton(onClick = {
                    flatten = !flatten
                    if (!flatten) currentFolder = ""
                    focusTargetKey = null
                }) {
                    Icon(if (flatten) Icons.Filled.Folder else Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Text(if (flatten) " 依資料夾顯示" else " 單一清單")
                }
                OutlinedButton(onClick = {
                    sort = VideoSortOption.entries[(sort.ordinal + 1) % VideoSortOption.entries.size]
                }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                    Text(" ${sort.label}")
                }
            }
        }

        when (val current = state) {
            is TvManifestState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is TvManifestState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TvEmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = "無法取得影片清單",
                    description = "請確認對方裝置仍在線上、而且還在分享影片,再回上一頁重試一次。",
                )
            }
            is TvManifestState.RemoteWaiting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TvEmptyState(
                    icon = Icons.Filled.SettingsRemote,
                    title = "遙控模式已開啟",
                    description = "正在等待主機選擇影片,選好後會自動開始播放。",
                )
            }
            is TvManifestState.Loaded -> {
                val indexByPath = remember(visibleEntries) {
                    visibleEntries.withIndex().associate { (index, entry) -> entry.relativePath to index }
                }
                val folderVideoCounts = remember(allEntries, currentFolder, subfolders) {
                    subfolders.associateWith { name ->
                        countVideosUnder(allEntries, if (currentFolder.isEmpty()) name else "$currentFolder/$name")
                    }
                }

                if (!flatten && currentFolder.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { goToFolder("") }) {
                            Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("首頁")
                        }
                        var accumulated = ""
                        val segments = currentFolder.split('/')
                        segments.forEachIndexed { i, segment ->
                            Text("/", modifier = Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
                            val path = accumulated
                            if (i == segments.lastIndex) {
                                Text(segment, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                OutlinedButton(onClick = { goToFolder(path) }) { Text(segment) }
                            }
                        }
                    }
                }

                if (subfolders.isEmpty() && visibleEntries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TvEmptyState(icon = Icons.Filled.FolderOff, title = "這個資料夾是空的", description = null)
                    }
                    return@Column
                }

                // 要拿焦點的那一格:剛離開的資料夾(返回上一層時),找不到就是第一格。
                val focusKey = focusTargetKey?.takeIf { key -> key.removePrefix("folder:") in subfolders }
                    ?: subfolders.firstOrNull()?.let { "folder:$it" }
                    ?: visibleEntries.firstOrNull()?.relativePath
                fun Modifier.focusTargetIf(key: String) =
                    if (key == focusKey) this.focusRequester(gridFocusRequester) else this

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
                        val count = folderVideoCounts[name] ?: 0
                        Card(
                            onClick = { goToFolder(childPath) },
                            modifier = Modifier.fillMaxWidth().focusTargetIf("folder:$name"),
                            border = tvBrandCardBorder(),
                            scale = tvBrandCardScale(),
                        ) {
                            Column {
                                Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(48.dp))
                                }
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // minLines = 2:名稱長短不一時卡片高度仍一致,格線才排得整齊。
                                    Text(name, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("$count 部影片", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    items(visibleEntries, key = { it.relativePath }) { entry ->
                        val index = indexByPath.getValue(entry.relativePath)
                        Card(
                            onClick = { onPlay(visibleEntries, index, false) },
                            modifier = Modifier.fillMaxWidth().focusTargetIf(entry.relativePath),
                            border = tvBrandCardBorder(),
                            scale = tvBrandCardScale(),
                        ) {
                            Column {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    VideoThumbnail(
                                        url = videoThumbnailUrl(server.host, server.port, entry.relativePath),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Icon(
                                        Icons.Filled.PlayCircle,
                                        contentDescription = "播放",
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.align(Alignment.Center).size(40.dp),
                                    )
                                }
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
