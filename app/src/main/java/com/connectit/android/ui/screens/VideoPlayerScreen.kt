package com.connectit.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.connectit.android.R
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.net.videoMediaUrl
import com.connectit.android.net.videoThumbnailUrl
import com.connectit.android.repo.PlaybackPreferences
import com.connectit.android.ui.components.AdaptiveNavigationBreakpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val SkipDurationMs = 10_000L
private val AutoplayCountdownSeconds = 5
private val SpeedOptions = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

/**
 * 用 ExoPlayer(Media3)原生播放,取代 WebView 播放 `<video>`——後者內嵌在 Compose 的
 * AndroidView 裡有已知的相容性問題(聲音正常但畫面全黑,在真機上重現過,連 WebChromeClient
 * 都無法解決)。播放控制列整組自己刻(而不是用 PlayerView 內建的),對應 Windows 觀看頁
 * BuildWatchPageScript 的功能:上一部/下一部、快轉倒轉 10 秒、播放速度、音量、自動播放下一部
 * (含可取消的倒數提示)、記住播放進度/音量/速度、全螢幕。
 *
 * 一次只讓 ExoPlayer 準備一個 MediaItem(而不是把整份清單塞進 ExoPlayer 自己的播放清單),
 * 换片全部由這裡手動呼叫 [playIndex] 控制,行為(尤其是「播完要不要自動接下一部」的時機)
 * 才會跟網頁版邏輯對得整齊,不用跟 ExoPlayer 內建的播放清單/自動前進機制打架。
 */
@Composable
fun VideoPlayerScreen(
    server: DiscoveredDevice,
    entries: List<VideoManifestEntry>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val prefs = remember { PlaybackPreferences(context.applicationContext) }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    var currentIndex by remember { mutableStateOf(startIndex) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewFraction by remember { mutableStateOf(0f) }

    var autoplayNext by remember { mutableStateOf(true) }
    var volume by remember { mutableStateOf(1f) }
    var mutedVolume by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableStateOf(1f) }
    var isFullscreen by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf<Int?>(null) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    // 控制列疊在影片上,只有「滑鼠移到影片上/點擊影片/影片暫停」才顯示,對應網頁版
    // BuildWatchPageScript 的 showControls() 邏輯:播放中沒有互動就在一段時間後淡出。
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteractionTick by remember { mutableStateOf(0) }
    fun showControls() {
        controlsVisible = true
        controlsInteractionTick++
    }
    LaunchedEffect(controlsInteractionTick, isPlaying) {
        if (isPlaying) {
            delay(2_500)
            controlsVisible = false
        }
    }

    fun videoKey(entry: VideoManifestEntry) = "${server.host}:${server.port}/${entry.relativePath}"

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        countdownSeconds = null
    }

    fun playIndex(index: Int) {
        if (index !in entries.indices) return
        cancelCountdown()
        currentIndex = index
        val entry = entries[index]
        val mediaItem = MediaItem.Builder()
            .setUri(videoMediaUrl(server.host, server.port, entry.relativePath))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.name).build())
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        scope.launch {
            val saved = prefs.getPosition(videoKey(entry))
            if (saved != null && saved > 5_000L) {
                exoPlayer.seekTo(saved)
            }
        }
    }

    fun startAutoplayCountdown(nextIndex: Int) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var remaining = AutoplayCountdownSeconds
            countdownSeconds = remaining
            while (remaining > 0) {
                delay(1000)
                remaining--
                countdownSeconds = remaining
            }
            countdownSeconds = null
            playIndex(nextIndex)
        }
    }

    fun applyFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun setVolumeAndPersist(value: Float) {
        volume = value.coerceIn(0f, 1f)
        mutedVolume = null
        exoPlayer.volume = volume
        scope.launch { prefs.setVolume(volume) }
    }

    fun toggleMute() {
        val savedVolume = mutedVolume
        if (savedVolume != null) {
            mutedVolume = null
            volume = savedVolume
            exoPlayer.volume = savedVolume
        } else {
            mutedVolume = volume
            volume = 0f
            exoPlayer.volume = 0f
        }
    }

    fun togglePlayPause() {
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0)
            exoPlayer.play()
        } else if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    // 初始套用上次記住的音量/速度/自動播放設定,並播放使用者點的那一部。
    LaunchedEffect(Unit) {
        volume = prefs.volume.first()
        speed = prefs.speed.first()
        autoplayNext = prefs.autoplayNext.first()
        exoPlayer.volume = volume
        exoPlayer.setPlaybackSpeed(speed)
        playIndex(startIndex)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val finished = entries.getOrNull(currentIndex) ?: return
                    scope.launch { prefs.clearPosition(videoKey(finished)) }
                    val nextIndex = currentIndex + 1
                    if (autoplayNext && nextIndex < entries.size) {
                        startAutoplayCountdown(nextIndex)
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // 每隔幾秒記一次目前的播放進度(對應網頁版在 timeupdate 事件裡持續寫 localStorage)。
    LaunchedEffect(currentIndex) {
        while (isActive) {
            delay(3_000)
            val entry = entries.getOrNull(currentIndex) ?: continue
            if (exoPlayer.currentPosition > 2_000L) {
                prefs.savePosition(videoKey(entry), exoPlayer.currentPosition)
            }
        }
    }

    // 輪詢目前播放位置/總長度——ExoPlayer 沒有現成的 Flow 可以觀察,用簡單的 polling 就夠了。
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            if (!isSeeking) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val entry = entries.getOrNull(currentIndex)
            if (entry != null && exoPlayer.currentPosition > 2_000L) {
                scope.launch { prefs.savePosition(videoKey(entry), exoPlayer.currentPosition) }
            }
            exoPlayer.release()
            applyFullscreen(false)
        }
    }

    BackHandler {
        when {
            isFullscreen -> applyFullscreen(false)
            else -> onBack()
        }
    }

    val currentEntry = entries.getOrNull(currentIndex)

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text(currentEntry?.name ?: server.displayName, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(if (isFullscreen) PaddingValues(0.dp) else padding),
        ) {
            // 折疊機攤開、平板橫向這種寬螢幕才顯示側邊清單(對應 Windows 觀看頁
            // `main.watch{grid-template-columns:minmax(0,1fr) 360px}` 的側邊清單),
            // 手機直向寬度不夠、或全螢幕播放時都只顯示播放器本身。
            val showSidebar = !isFullscreen && maxWidth >= AdaptiveNavigationBreakpoint && entries.size > 1

            val playerContent: @Composable () -> Unit = {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = if (isFullscreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        },
                    ) {
                        val videoInteractionSource = remember { MutableInteractionSource() }
                        val isVideoHovered by videoInteractionSource.collectIsHoveredAsState()
                        LaunchedEffect(isVideoHovered) { if (isVideoHovered) showControls() }

                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .hoverable(videoInteractionSource)
                                .clickable(
                                    interactionSource = videoInteractionSource,
                                    indication = null,
                                ) { showControls() },
                            factory = { ctx ->
                                (LayoutInflater.from(ctx).inflate(R.layout.view_video_player, null) as PlayerView).apply {
                                    player = exoPlayer
                                }
                            },
                        )

                        countdownSeconds?.let { seconds ->
                            Card(
                                // 控制列現在疊在影片底部,倒數卡片要多留一點底部空間才不會被蓋住
                                // (跟網頁版把 .next-overlay 往上挪、避開疊加控制列的道理一樣)。
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, top = 16.dp, bottom = 96.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text("即將播放下一部…($seconds)")
                                    TextButton(onClick = { cancelCountdown() }) { Text("取消") }
                                }
                            }
                        }

                        // 這裡同時處在 Box 和外層 Column 的作用域裡,BoxScope/ColumnScope 都各自有一份
                        // 簽章相同的 AnimatedVisibility 擴充函式,implicit receiver 沒辦法自動判斷要用
                        // 哪一個,所以用完整路徑指名呼叫最基本、不吃 receiver 的那個多載版本。
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible || !isPlaying,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            VideoControls(
                                isPlaying = isPlaying,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                isSeeking = isSeeking,
                                seekPreviewFraction = seekPreviewFraction,
                                onSeekChange = { showControls(); isSeeking = true; seekPreviewFraction = it },
                                onSeekFinished = {
                                    exoPlayer.seekTo((seekPreviewFraction * durationMs).toLong())
                                    isSeeking = false
                                },
                                hasPrevious = currentIndex > 0,
                                hasNext = currentIndex < entries.size - 1,
                                onPrevious = { showControls(); playIndex(currentIndex - 1) },
                                onNext = { showControls(); playIndex(currentIndex + 1) },
                                onRewind = {
                                    showControls()
                                    exoPlayer.seekTo((exoPlayer.currentPosition - SkipDurationMs).coerceAtLeast(0L))
                                },
                                onForward = {
                                    showControls()
                                    val max = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                                    exoPlayer.seekTo((exoPlayer.currentPosition + SkipDurationMs).coerceAtMost(max))
                                },
                                onPlayPause = { showControls(); togglePlayPause() },
                                autoplayNext = autoplayNext,
                                onAutoplayNextChange = { checked ->
                                    showControls()
                                    autoplayNext = checked
                                    scope.launch { prefs.setAutoplayNext(checked) }
                                    if (!checked) cancelCountdown()
                                },
                                speed = speed,
                                onSpeedChange = { option ->
                                    showControls()
                                    speed = option
                                    exoPlayer.setPlaybackSpeed(option)
                                    scope.launch { prefs.setSpeed(option) }
                                },
                                volume = volume,
                                onVolumeChange = { showControls(); setVolumeAndPersist(it) },
                                onToggleMute = { showControls(); toggleMute() },
                                isFullscreen = isFullscreen,
                                onToggleFullscreen = { showControls(); applyFullscreen(!isFullscreen) },
                            )
                        }
                    }

                    if (!isFullscreen) {
                        currentEntry?.let {
                            Text(
                                it.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            if (showSidebar) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { playerContent() }
                    VideoSidebar(
                        entries = entries,
                        currentIndex = currentIndex,
                        server = server,
                        onSelect = { index -> playIndex(index) },
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                }
            } else {
                playerContent()
            }
        }
    }
}

@Composable
private fun VideoControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    seekPreviewFraction: Float,
    onSeekChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPlayPause: () -> Unit,
    autoplayNext: Boolean,
    onAutoplayNextChange: (Boolean) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    var settingsMenuExpanded by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.85f),
                    ),
                )
                .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        ) {
            Slider(
                value = if (isSeeking) seekPreviewFraction else fractionOf(positionMs, durationMs),
                onValueChange = onSeekChange,
                onValueChangeFinished = onSeekFinished,
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onPrevious, enabled = hasPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一部")
                }
                IconButton(onClick = onRewind) {
                    Icon(Icons.Filled.FastRewind, contentDescription = "倒退 10 秒")
                }
                IconButton(onClick = onPlayPause) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "播放/暫停")
                }
                IconButton(onClick = onForward) {
                    Icon(Icons.Filled.FastForward, contentDescription = "快轉 10 秒")
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一部")
                }
                Text(
                    "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Spacer(modifier = Modifier.weight(1f))

                // 音量、自動播放下一部、播放速度都收進同一個「設定」選單,對應網頁版把這三個
                // 選項合併進單一齒輪圖示下拉選單的做法。
                Box {
                    IconButton(onClick = { settingsMenuExpanded = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                    DropdownMenu(expanded = settingsMenuExpanded, onDismissRequest = { settingsMenuExpanded = false }) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            IconButton(onClick = onToggleMute) {
                                Icon(
                                    if (volume <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "靜音",
                                )
                            }
                            Slider(
                                value = volume,
                                onValueChange = onVolumeChange,
                                modifier = Modifier.width(140.dp),
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAutoplayNextChange(!autoplayNext) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("自動播放下一部")
                            Checkbox(checked = autoplayNext, onCheckedChange = onAutoplayNextChange)
                        }

                        Text(
                            "播放速度",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        SpeedOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("${option}x") },
                                onClick = { onSpeedChange(option) },
                                leadingIcon = if (option == speed) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = "全螢幕",
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoSidebar(
    entries: List<VideoManifestEntry>,
    currentIndex: Int,
    server: DiscoveredDevice,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.relativePath }) { index, entry ->
            val isCurrent = index == currentIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .clickable(enabled = !isCurrent) { onSelect(index) }
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.width(110.dp).aspectRatio(16f / 9f)) {
                    AsyncImage(
                        model = videoThumbnailUrl(server.host, server.port, entry.relativePath),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column {
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall)
                    if (isCurrent) {
                        Text(
                            "正在播放",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
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

private fun fractionOf(position: Long, duration: Long): Float =
    if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
