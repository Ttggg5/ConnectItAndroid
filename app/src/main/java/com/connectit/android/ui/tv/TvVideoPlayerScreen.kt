package com.connectit.android.ui.tv

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.OutlinedButton
import com.connectit.android.R
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.net.fetchControlState
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
 * TV 版觀看畫面:沒有滑鼠 hover、沒有觸控,控制列改成任何遙控器按鍵都會喚出(對應手機版滑鼠移到
 * 影片上/點擊才顯示),喚出前 左/右鍵直接當作快轉/倒轉 10 秒、確認鍵當作播放/暫停——這是電視
 * 播放器常見的操作手感(YouTube/Netflix 的 TV app 都是這樣),不用先喚出控制列、把焦點移到按鈕上
 * 才能操作。進度條本身也是可 focus 的元件(見 TvSeekBar),focus 在上面時左右鍵直接跳轉進度,
 * 跟其他按鈕一樣走方向鍵在控制列內移動焦點。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvVideoPlayerScreen(
    server: DiscoveredDevice,
    entries: List<VideoManifestEntry>,
    startIndex: Int,
    onBack: () -> Unit,
    onExitRemoteControl: () -> Unit,
    remoteControlHint: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PlaybackPreferences(context.applicationContext) }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    val rootFocusRequester = remember { FocusRequester() }

    var currentIndex by remember { mutableStateOf(startIndex) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var volume by remember { mutableStateOf(1f) }
    var speed by remember { mutableStateOf(1f) }
    var autoplayNext by remember { mutableStateOf(true) }
    var countdownSeconds by remember { mutableStateOf<Int?>(null) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    // 遠端控制模式:主機正在控制播放時鎖住手動控制列,改成跟隨輪詢到的狀態播放——跟手機版
    // VideoPlayerScreen 是同一套輪詢邏輯(見下方對 fetchControlState 的說明)。
    var remoteEnabled by remember { mutableStateOf(remoteControlHint) }
    var lastRemoteResyncAt by remember { mutableStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteractionTick by remember { mutableStateOf(0) }
    fun showControls() {
        controlsVisible = true
        controlsInteractionTick++
    }
    LaunchedEffect(controlsInteractionTick, isPlaying) {
        if (isPlaying) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) rootFocusRequester.requestFocus()
    }
    LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }

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
        if (!remoteEnabled) {
            scope.launch {
                val saved = prefs.getPosition(videoKey(entry))
                if (saved != null && saved > 5_000L) exoPlayer.seekTo(saved)
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

    fun seekBy(deltaMs: Long) {
        val max = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceIn(0L, max))
    }

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
                    if (autoplayNext && nextIndex in entries.indices) startAutoplayCountdown(nextIndex)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(currentIndex) {
        while (isActive) {
            delay(3_000)
            val entry = entries.getOrNull(currentIndex) ?: continue
            if (exoPlayer.currentPosition > 2_000L) prefs.savePosition(videoKey(entry), exoPlayer.currentPosition)
        }
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    LaunchedEffect(server) {
        while (isActive) {
            val state = fetchControlState(server.host, server.port)
            if (state != null) {
                remoteEnabled = state.enabled
                if (state.enabled) {
                    val targetIndex = state.videoRelativePath
                        ?.let { path -> entries.indexOfFirst { it.relativePath == path } }
                        ?.takeIf { it >= 0 }
                    if (targetIndex != null && targetIndex != currentIndex) playIndex(targetIndex)

                    if (state.isPlaying && !exoPlayer.isPlaying) exoPlayer.play()
                    else if (!state.isPlaying && exoPlayer.isPlaying) exoPlayer.pause()

                    val cooldownActive = System.currentTimeMillis() - lastRemoteResyncAt < 1_000L
                    if (!cooldownActive && kotlin.math.abs(exoPlayer.currentPosition - state.positionMs) > 1_500L) {
                        val duration = exoPlayer.duration
                        val target = if (duration > 0) state.positionMs.coerceIn(0L, duration) else state.positionMs.coerceAtLeast(0L)
                        exoPlayer.seekTo(target)
                        lastRemoteResyncAt = System.currentTimeMillis()
                    }

                    if (exoPlayer.playbackParameters.speed != state.playbackRate.toFloat()) {
                        exoPlayer.setPlaybackSpeed(state.playbackRate.toFloat())
                    }
                    val targetVolume = if (state.muted) 0f else state.volume.toFloat()
                    if (kotlin.math.abs(exoPlayer.volume - targetVolume) > 0.001f) exoPlayer.volume = targetVolume
                }
            }
            delay(800)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val entry = entries.getOrNull(currentIndex)
            if (entry != null && exoPlayer.currentPosition > 2_000L) {
                scope.launch { prefs.savePosition(videoKey(entry), exoPlayer.currentPosition) }
            }
            exoPlayer.release()
        }
    }

    fun navigateBack() {
        if (remoteEnabled) onExitRemoteControl() else onBack()
    }
    BackHandler { navigateBack() }

    val currentEntry = entries.getOrNull(currentIndex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (remoteEnabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { showControls(); togglePlayPause(); true }
                    Key.MediaFastForward -> { showControls(); seekBy(SkipDurationMs); true }
                    Key.MediaRewind -> { showControls(); seekBy(-SkipDurationMs); true }
                    Key.MediaNext -> { showControls(); playIndex(currentIndex + 1); true }
                    Key.MediaPrevious -> { showControls(); playIndex(currentIndex - 1); true }
                    else -> if (controlsVisible) {
                        // 控制列已經顯示,交給正常的焦點系統在按鈕間移動——只在這裡重置自動隱藏的
                        // 計時器,避免使用者還在操作控制列時它自己先淡出。
                        showControls()
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionLeft -> { showControls(); seekBy(-SkipDurationMs); true }
                            Key.DirectionRight -> { showControls(); seekBy(SkipDurationMs); true }
                            Key.DirectionCenter, Key.Enter -> { showControls(); togglePlayPause(); true }
                            else -> { showControls(); true }
                        }
                    }
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_video_player, null) as PlayerView).apply {
                    player = exoPlayer
                }
            },
        )

        countdownSeconds?.let { seconds ->
            Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 140.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("即將播放下一部…($seconds)")
                    OutlinedButton(onClick = { cancelCountdown() }) { Text("取消") }
                }
            }
        }

        if (remoteEnabled) {
            Surface(modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.SettingsRemote, contentDescription = null)
                    Text("遙控模式中,由主機控制播放")
                }
            }
        } else {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                TvVideoControls(
                    title = currentEntry?.name ?: server.displayName,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    hasPrevious = currentIndex > 0,
                    hasNext = currentIndex < entries.lastIndex,
                    onPrevious = { showControls(); playIndex(currentIndex - 1) },
                    onNext = { showControls(); playIndex(currentIndex + 1) },
                    onRewind = { showControls(); seekBy(-SkipDurationMs) },
                    onForward = { showControls(); seekBy(SkipDurationMs) },
                    onSeek = { deltaMs -> showControls(); seekBy(deltaMs) },
                    onPlayPause = { showControls(); togglePlayPause() },
                    speed = speed,
                    onCycleSpeed = {
                        showControls()
                        val nextSpeed = SpeedOptions[(SpeedOptions.indexOf(speed) + 1).mod(SpeedOptions.size)]
                        speed = nextSpeed
                        exoPlayer.setPlaybackSpeed(nextSpeed)
                        scope.launch { prefs.setSpeed(nextSpeed) }
                    },
                    autoplayNext = autoplayNext,
                    onToggleAutoplay = {
                        showControls()
                        autoplayNext = !autoplayNext
                        scope.launch { prefs.setAutoplayNext(autoplayNext) }
                        if (!autoplayNext) cancelCountdown()
                    },
                    muted = volume <= 0f,
                    onToggleMute = {
                        showControls()
                        volume = if (volume > 0f) 0f else 1f
                        exoPlayer.volume = volume
                        scope.launch { prefs.setVolume(volume) }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvVideoControls(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    speed: Float,
    onCycleSpeed: () -> Unit,
    autoplayNext: Boolean,
    onToggleAutoplay: () -> Unit,
    muted: Boolean,
    onToggleMute: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f)))
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)

        TvSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        Text(
            "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onToggleMute) {
                Icon(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "靜音",
                )
            }
            IconButton(onClick = onToggleAutoplay) {
                Icon(
                    if (autoplayNext) Icons.Filled.Repeat else Icons.Filled.RepeatOne,
                    contentDescription = if (autoplayNext) "自動播放下一部:開" else "自動播放下一部:關",
                )
            }
            OutlinedButton(onClick = onCycleSpeed) {
                Icon(Icons.Filled.Speed, contentDescription = null)
                Text(" ${formatSpeedLabel(speed)}")
            }
        }
    }
}

/**
 * 可 focus 的進度條:遙控器移到上面時,左右鍵不再是控制列內的焦點移動,而是直接以
 * [SkipDurationMs] 為單位跳轉播放進度(上下鍵維持正常的焦點移動,離開這裡回到按鈕列)。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fraction = fractionOf(positionMs, durationMs)

    Box(
        modifier = modifier
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onSeek(-SkipDurationMs); true }
                    Key.DirectionRight -> { onSeek(SkipDurationMs); true }
                    else -> false
                }
            }
            .height(if (focused) 10.dp else 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.25f))
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
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

private fun formatSpeedLabel(value: Float): String {
    val trimmed = if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
    return "${trimmed}x"
}
