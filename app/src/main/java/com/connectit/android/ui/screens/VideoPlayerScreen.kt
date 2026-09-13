package com.connectit.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.connectit.android.R
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.net.videoMediaUrl
import com.connectit.android.repo.PlaybackPreferences
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
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf<Int?>(null) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

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
        Column(modifier = Modifier.fillMaxSize().padding(if (isFullscreen) androidx.compose.foundation.layout.PaddingValues(0.dp) else padding)) {
            Box(
                modifier = if (isFullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                },
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().clickable { togglePlayPause() },
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.view_video_player, null) as PlayerView).apply {
                            player = exoPlayer
                        }
                    },
                )

                countdownSeconds?.let { seconds ->
                    Card(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
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

            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
                Slider(
                    value = if (isSeeking) seekPreviewFraction else fractionOf(positionMs, durationMs),
                    onValueChange = {
                        isSeeking = true
                        seekPreviewFraction = it
                    },
                    onValueChangeFinished = {
                        val target = (seekPreviewFraction * durationMs).toLong()
                        exoPlayer.seekTo(target)
                        isSeeking = false
                    },
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { playIndex(currentIndex - 1) }, enabled = currentIndex > 0) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "上一部")
                    }
                    IconButton(onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - SkipDurationMs).coerceAtLeast(0L)) }) {
                        Icon(Icons.Filled.FastRewind, contentDescription = "倒退 10 秒")
                    }
                    IconButton(onClick = { togglePlayPause() }) {
                        Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "播放/暫停")
                    }
                    IconButton(onClick = {
                        val max = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                        exoPlayer.seekTo((exoPlayer.currentPosition + SkipDurationMs).coerceAtMost(max))
                    }) {
                        Icon(Icons.Filled.FastForward, contentDescription = "快轉 10 秒")
                    }
                    IconButton(onClick = { playIndex(currentIndex + 1) }, enabled = currentIndex < entries.size - 1) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "下一部")
                    }
                    Text(
                        "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = autoplayNext,
                            onCheckedChange = { checked ->
                                autoplayNext = checked
                                scope.launch { prefs.setAutoplayNext(checked) }
                                if (!checked) cancelCountdown()
                            },
                        )
                        Text("自動播放下一部", style = MaterialTheme.typography.bodySmall)
                    }

                    Box {
                        TextButton(onClick = { speedMenuExpanded = true }) { Text("${speed}x") }
                        DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                            SpeedOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("${option}x") },
                                    onClick = {
                                        speed = option
                                        exoPlayer.setPlaybackSpeed(option)
                                        scope.launch { prefs.setSpeed(option) }
                                        speedMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { toggleMute() }) {
                            Icon(
                                if (volume <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "靜音",
                            )
                        }
                        Slider(
                            value = volume,
                            onValueChange = { setVolumeAndPersist(it) },
                            modifier = Modifier.width(100.dp),
                        )
                    }

                    IconButton(onClick = { applyFullscreen(!isFullscreen) }) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = "全螢幕",
                        )
                    }
                }
            }
        }
    }
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
