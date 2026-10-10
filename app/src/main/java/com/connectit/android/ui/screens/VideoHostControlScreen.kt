package com.connectit.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.connectit.android.net.videoThumbnailUrl
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.RemoteAutoplayCountdown
import com.connectit.android.service.RemotePlaylistSettings
import com.connectit.android.service.VideoHostUiState
import com.connectit.android.ui.components.AdaptiveContentWidth
import com.connectit.android.ui.components.AdaptiveNavigationBreakpoint
import com.connectit.android.video.HostManifestEntry
import com.connectit.android.video.PlaybackControlState
import com.connectit.android.video.VideoSort
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val SpeedOptions = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

private const val SeekStepMs = 10_000L

/** 寬螢幕(折疊機攤開、平板)時右側控制面板的寬度。 */
private val WideControlPanelWidth = 380.dp

/**
 * 主機端的「遙控器」畫面:開/關遠端控制模式、從自己分享的影片庫選片、播放/暫停、拖曳進度。
 * 主機本身不需要在這裡播放影片——這裡下的每個指令都直接寫進 [ConnectItService] 持有的
 * `PlaybackControlState`,再由觀眾端(見 VideoPlayerScreen.kt / Windows 端觀看頁)透過推送同步套用。
 *
 * 版面:遙控模式關閉時是一頁說明 + 開啟按鈕;開啟後手機上是「上方影片庫可捲動、下方固定播放控制列」
 * (像音樂 App 的迷你播放器,控制鍵永遠在手指範圍內),寬螢幕則是左邊影片庫、右邊完整控制面板。
 */
@Composable
fun VideoHostControlScreen(service: ConnectItService, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val hostState by service.videoHostState.collectAsState()
    val playlistSettings by service.remotePlaylist.collectAsState()
    val autoplayCountdown by service.remoteAutoplayCountdown.collectAsState()
    var snapshot by remember { mutableStateOf(service.currentPlaybackSnapshot()) }
    var durationMs by remember { mutableStateOf<Long?>(null) }

    // 用本機輪詢(不是網路請求)持續更新進度條,讓「正在播放」時進度看起來會自己走動,
    // 而不是要等下一次下指令才刷新。
    LaunchedEffect(Unit) {
        while (isActive) {
            snapshot = service.currentPlaybackSnapshot()
            delay(500)
        }
    }

    // 主機平常完全不解析媒體檔案,唯獨時間軸需要知道總長度才能畫,所以只在選片改變時才探測一次
    // (見 VideoDurationProbe),探測結果由 VideoHostServer 快取,同一支影片不會重複探測。
    LaunchedEffect(snapshot.videoRelativePath) {
        durationMs = null
        durationMs = snapshot.videoRelativePath?.let { service.hostVideoDurationMs(it) }
    }

    val refresh = { snapshot = service.currentPlaybackSnapshot() }
    val running = hostState as? VideoHostUiState.Running

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("遙控模式")
                        if (running != null) {
                            Text(
                                "${running.folderName} · ${running.videoCount} 部影片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (running != null && snapshot.enabled) {
                        TextButton(onClick = { service.setRemoteControlEnabled(false); refresh() }) {
                            Text("結束遙控")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                running == null -> NotSharingMessage()
                !snapshot.enabled -> RemoteControlIntro(
                    videoCount = running.videoCount,
                    onEnable = { service.setRemoteControlEnabled(true); refresh() },
                )
                else -> {
                    // 依遙控面板選的排序/隨機播放排好的播放清單,只在設定改變或重新分享時才重算。
                    val entries = remember(playlistSettings, running) { service.remotePlaylistEntries() }
                    val currentIndex = entries.indexOfFirst { it.relativePath == snapshot.videoRelativePath }
                    val controls = HostControls(
                        service = service,
                        snapshot = snapshot,
                        currentEntry = entries.getOrNull(currentIndex),
                        durationMs = durationMs,
                        hasPrevious = currentIndex > 0,
                        hasNext = currentIndex in 0 until entries.size - 1,
                        port = running.port,
                        autoplayCountdown = autoplayCountdown,
                        refresh = refresh,
                    )
                    val playlistControls: @Composable () -> Unit = {
                        PlaylistOptions(service, playlistSettings)
                    }
                    val onSelect = { entry: HostManifestEntry ->
                        service.remoteControlPlayVideo(entry.relativePath)
                        refresh()
                    }

                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        if (maxWidth >= AdaptiveNavigationBreakpoint) {
                            Row(Modifier.fillMaxSize()) {
                                VideoLibrary(
                                    entries = entries,
                                    currentPath = snapshot.videoRelativePath,
                                    port = running.port,
                                    onSelect = onSelect,
                                    playlistControls = playlistControls,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                VerticalDivider()
                                Column(
                                    Modifier
                                        .width(WideControlPanelWidth)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                        .padding(20.dp),
                                ) {
                                    ControlPanel(controls, compact = false)
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                VideoLibrary(
                                    entries = entries,
                                    currentPath = snapshot.videoRelativePath,
                                    port = running.port,
                                    onSelect = onSelect,
                                    playlistControls = playlistControls,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        ControlPanel(controls, compact = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 控制面板需要的狀態與指令,打包起來避免手機/寬螢幕兩種版面各自傳一長串參數。 */
private class HostControls(
    val service: ConnectItService,
    val snapshot: PlaybackControlState.Snapshot,
    val currentEntry: HostManifestEntry?,
    val durationMs: Long?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val port: Int,
    val autoplayCountdown: RemoteAutoplayCountdown?,
    val refresh: () -> Unit,
)

@Composable
private fun NotSharingMessage() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("尚未開始分享影片", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "請先回到「影片」頁選擇資料夾開始分享,才能使用遙控模式。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 遙控模式關閉時的說明頁:用三個步驟說明開啟後會發生什麼事,再給一個明確的開啟按鈕。 */
@Composable
private fun RemoteControlIntro(videoCount: Int, onEnable: () -> Unit) {
    AdaptiveContentWidth {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SettingsRemote,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "用這台裝置遙控所有觀看端",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "適合一起看片:由你決定看哪一部、什麼時候播放或暫停,大家的畫面都會同步。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    IntroStep(1, "開啟遙控模式", "觀看端會鎖定成「只能觀看」,不能自行選片或操作播放。")
                    IntroStep(2, "從影片庫選一部影片", "從這次分享的 $videoCount 部影片中點選,所有觀看端會立刻開始播放。")
                    IntroStep(3, "在這裡控制播放", "播放/暫停、拖曳進度、切換上一部/下一部、調整音量與速度。")
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.SettingsRemote, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("開啟遙控模式", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "隨時可以按右上角的「結束遙控」,觀看端就會恢復自由觀看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun IntroStep(number: Int, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 影片庫:最上面是搜尋框(影片很多時才找得到),下面是縮圖格狀清單,目前播放的那一部會標示出來。 */
@Composable
private fun VideoLibrary(
    entries: List<HostManifestEntry>,
    currentPath: String?,
    port: Int,
    onSelect: (HostManifestEntry) -> Unit,
    playlistControls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(entries, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) entries else entries.filter { it.relativePath.contains(trimmed, ignoreCase = true) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "點選影片,所有觀看端就會開始播放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                playlistControls()
                if (entries.size > 1) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜尋影片") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清除搜尋")
                                }
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (entries.isEmpty()) "這個資料夾裡沒有影片。" else "找不到符合「${query.trim()}」的影片。",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        items(filtered, key = { it.relativePath }) { entry ->
            VideoCard(
                entry = entry,
                isCurrent = entry.relativePath == currentPath,
                port = port,
                onClick = { onSelect(entry) },
            )
        }
    }
}

@Composable
private fun VideoCard(entry: HostManifestEntry, isCurrent: Boolean, port: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCurrent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            VideoThumbnail(port = port, relativePath = entry.relativePath, modifier = Modifier.fillMaxSize())
            if (isCurrent) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "播放中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            folderOf(entry.relativePath)?.let { folder ->
                Text(
                    folder,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 縮圖底下先墊一層佔位圖示:縮圖還在產生或產生失敗(例如格式不支援)時,至少不會是一塊空白。 */
@Composable
private fun VideoThumbnail(port: Int, relativePath: String, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Movie,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        AsyncImage(
            model = videoThumbnailUrl("127.0.0.1", port, relativePath),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * 播放控制面板。[compact] 為手機底部控制列:縮圖縮小放在標題旁、按鈕間距收緊;
 * 寬螢幕右側面板則放大縮圖、把速度與音量各自獨立成一區。
 */
@Composable
private fun ControlPanel(controls: HostControls, compact: Boolean) {
    val snapshot = controls.snapshot
    val service = controls.service
    val entry = controls.currentEntry
    val duration = controls.durationMs?.takeIf { it > 0 }
    val hasVideo = entry != null

    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewFraction by remember { mutableStateOf(0f) }
    val displayedPositionMs = if (isSeeking && duration != null) {
        (seekPreviewFraction * duration).toLong()
    } else {
        snapshot.positionMs
    }

    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
        SyncStatusLabel()

        // 目前播放的影片
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (entry != null) {
                    VideoThumbnail(
                        port = controls.port,
                        relativePath = entry.relativePath,
                        modifier = Modifier.width(80.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                NowPlayingTitle(entry, Modifier.weight(1f))
            }
        } else {
            if (entry != null) {
                VideoThumbnail(
                    port = controls.port,
                    relativePath = entry.relativePath,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                )
            }
            NowPlayingTitle(entry, Modifier.fillMaxWidth())
        }

        // 時間軸
        Column {
            Slider(
                value = if (isSeeking) seekPreviewFraction else fractionOf(snapshot.positionMs, duration ?: 0L),
                onValueChange = {
                    isSeeking = true
                    seekPreviewFraction = it
                },
                onValueChangeFinished = {
                    if (duration != null) {
                        service.remoteControlSeek((seekPreviewFraction * duration).toLong())
                        controls.refresh()
                    }
                    isSeeking = false
                },
                enabled = hasVideo && duration != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (hasVideo) formatMs(displayedPositionMs) else "--:--",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        !hasVideo -> "--:--"
                        duration != null -> formatMs(duration)
                        else -> "讀取長度中…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        controls.autoplayCountdown?.let { countdown ->
            AutoplayCountdownBanner(countdown, onCancel = { service.remoteControlCancelAutoplay() })
        }

        // 主要播放按鈕
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { service.remoteControlSkip(-1); controls.refresh() },
                enabled = controls.hasPrevious,
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一部", modifier = Modifier.size(28.dp))
            }
            IconButton(
                onClick = {
                    service.remoteControlSeek((snapshot.positionMs - SeekStepMs).coerceAtLeast(0L))
                    controls.refresh()
                },
                enabled = hasVideo,
            ) {
                Icon(Icons.Filled.Replay10, contentDescription = "倒退 10 秒", modifier = Modifier.size(28.dp))
            }
            FilledIconButton(
                onClick = { service.remoteControlSetPlaying(!snapshot.isPlaying); controls.refresh() },
                enabled = hasVideo,
                modifier = Modifier.size(if (compact) 56.dp else 72.dp),
            ) {
                Icon(
                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (snapshot.isPlaying) "暫停" else "播放",
                    modifier = Modifier.size(if (compact) 32.dp else 40.dp),
                )
            }
            IconButton(
                onClick = {
                    val target = snapshot.positionMs + SeekStepMs
                    service.remoteControlSeek(if (duration != null) target.coerceAtMost(duration) else target)
                    controls.refresh()
                },
                enabled = hasVideo,
            ) {
                Icon(Icons.Filled.Forward10, contentDescription = "快轉 10 秒", modifier = Modifier.size(28.dp))
            }
            IconButton(
                onClick = { service.remoteControlSkip(1); controls.refresh() },
                enabled = controls.hasNext,
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一部", modifier = Modifier.size(28.dp))
            }
        }

        // 速度與音量
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedChip(controls)
                Spacer(Modifier.width(8.dp))
                VolumeControl(controls, Modifier.weight(1f))
            }
        } else {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("播放速度", style = MaterialTheme.typography.labelLarge)
            SpeedChip(controls)
            Text("音量", style = MaterialTheme.typography.labelLarge)
            VolumeControl(controls, Modifier.fillMaxWidth())
        }
    }
}

/** 影片庫上方的播放清單選項:排序方式、隨機播放、自動播放下一部(影響清單順序與上一部/下一部)。 */
@Composable
private fun PlaylistOptions(service: ConnectItService, settings: RemotePlaylistSettings) {
    var sortExpanded by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            AssistChip(
                onClick = { sortExpanded = true },
                label = { Text(settings.sort.label) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序方式", modifier = Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                VideoSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            service.remoteControlSetSort(option)
                            sortExpanded = false
                        },
                        leadingIcon = if (option == settings.sort) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = settings.shuffle,
            onClick = { service.remoteControlSetShuffle(!settings.shuffle) },
            label = { Text("隨機播放") },
            leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = settings.autoplayNext,
            onClick = { service.remoteControlSetAutoplayNext(!settings.autoplayNext) },
            label = { Text("自動播放下一部") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun AutoplayCountdownBanner(countdown: RemoteAutoplayCountdown, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "即將播放下一部:${countdown.nextName}(${countdown.secondsLeft})",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

/** 提醒主持人「現在觀看端都被鎖定、跟著這裡同步」,避免忘了遙控模式還開著。 */
@Composable
private fun SyncStatusLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "遙控中 · 觀看端畫面跟著這裡同步",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NowPlayingTitle(entry: HostManifestEntry?, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (entry != null) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text("尚未選擇影片", style = MaterialTheme.typography.titleMedium)
            Text(
                "從影片庫點一部影片開始播放",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeedChip(controls: HostControls) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(formatRate(controls.snapshot.playbackRate)) },
            leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = "播放速度", modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SpeedOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == 1.0) "1x(正常)" else formatRate(option)) },
                    onClick = {
                        controls.service.remoteControlSetPlaybackRate(option)
                        controls.refresh()
                        expanded = false
                    },
                    leadingIcon = if (option == controls.snapshot.playbackRate) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun VolumeControl(controls: HostControls, modifier: Modifier = Modifier) {
    val snapshot = controls.snapshot
    val service = controls.service
    val effectiveVolume = if (snapshot.muted) 0f else snapshot.volume.toFloat()

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { service.remoteControlSetMuted(!snapshot.muted); controls.refresh() }) {
            Icon(
                if (effectiveVolume <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (snapshot.muted) "取消靜音" else "靜音",
            )
        }
        Slider(
            value = effectiveVolume,
            onValueChange = {
                service.remoteControlSetVolume(it.toDouble())
                if (it > 0f && snapshot.muted) service.remoteControlSetMuted(false)
                controls.refresh()
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            "${(effectiveVolume * 100).toInt()}%",
            modifier = Modifier.widthIn(min = 44.dp).padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/** 影片在子資料夾裡時,回傳資料夾路徑讓同名影片分得出來;在根目錄則回傳 null。 */
private fun folderOf(relativePath: String): String? =
    relativePath.replace('\\', '/').substringBeforeLast('/', "").takeIf { it.isNotEmpty() }

private fun fractionOf(position: Long, duration: Long): Float =
    if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

private fun formatRate(rate: Double): String =
    if (rate % 1.0 == 0.0) "${rate.toInt()}x" else "${rate}x"

private fun formatMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
