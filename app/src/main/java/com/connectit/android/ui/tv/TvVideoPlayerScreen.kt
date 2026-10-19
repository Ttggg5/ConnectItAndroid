package com.connectit.android.ui.tv

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.connectit.android.R
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.net.observeControlState
import com.connectit.android.net.videoMediaUrl
import com.connectit.android.net.videoThumbnailUrl
import com.connectit.android.repo.PlaybackPreferences
import com.connectit.android.repo.RecentlyWatchedStore
import com.connectit.android.ui.components.NowPlayingBadge
import com.connectit.android.ui.components.VideoThumbnail
import com.connectit.android.ui.components.formatBytes
import com.connectit.android.ui.screens.VideoSortOption
import com.connectit.android.ui.screens.sortedByOption
import com.connectit.android.ui.theme.BrandColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val SkipDurationMs = 10_000L
private val SpeedOptions = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)
private const val VolumeStep = 0.1f

/**
 * TV 版觀看畫面:沒有滑鼠 hover、沒有觸控,控制列改成任何遙控器按鍵都會喚出(對應手機版滑鼠移到
 * 影片上/點擊才顯示),喚出前 左/右鍵直接當作快轉/倒轉 10 秒、確認鍵當作播放/暫停——這是電視
 * 播放器常見的操作手感(YouTube/Netflix 的 TV app 都是這樣),不用先喚出控制列、把焦點移到按鈕上
 * 才能操作。進度條、音量條本身也是可 focus 的元件(見 TvSeekBar/TvVolumeBar),focus 在上面時
 * 左右鍵直接調整,跟其他按鈕一樣走方向鍵在控制列內移動焦點。
 *
 * 功能對齊手機版 VideoPlayerScreen:上一部/下一部、快轉倒轉、音量(分段調整 + 靜音)、播放速度、
 * 自動播放下一部、隨機播放、可排序的播放清單(從右側滑出的面板)。
 *
 * 遠端控制模式下不顯示任何提示或控制列,畫面只有影片本身,播放完全跟隨主機。
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
    /** 從「繼續觀看」進來時,第一部影片的起始位置(見手機版 VideoPlayerScreen 的同名參數)。 */
    startPositionMs: Long? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recentlyWatched = remember { RecentlyWatchedStore.get(context) }
    var pendingStartPositionMs by remember { mutableStateOf(startPositionMs) }
    val prefs = remember { PlaybackPreferences(context.applicationContext) }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }

    var currentIndex by remember { mutableStateOf(startIndex) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var volume by remember { mutableStateOf(1f) }
    var mutedVolume by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableStateOf(1f) }
    var autoplayNext by remember { mutableStateOf(true) }
    var shuffle by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(VideoSortOption.NAME_ASC) }
    var playlistVisible by remember { mutableStateOf(false) }

    // 遠端控制模式:主機正在控制播放時鎖住手動控制列,改成跟隨主機推送的狀態播放——跟手機版
    // VideoPlayerScreen 是同一套邏輯(見下方對 observeControlState 的訂閱)。
    var remoteEnabled by remember { mutableStateOf(remoteControlHint) }
    var lastRemoteResyncAt by remember { mutableStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteractionTick by remember { mutableStateOf(0) }
    fun showControls() {
        controlsVisible = true
        controlsInteractionTick++
    }
    // 播放清單開著時不自動隱藏控制列,使用者可能還在慢慢挑片。
    LaunchedEffect(controlsInteractionTick, isPlaying, playlistVisible) {
        if (isPlaying && !playlistVisible) {
            delay(4_000)
            controlsVisible = false
        }
    }
    // 控制列一出現就把焦點放到播放/暫停鍵上,方向鍵可以直接在按鈕間移動;隱藏時焦點交回根節點,
    // 讓「未喚出控制列時左右鍵快轉倒轉」的處理接得到按鍵。
    LaunchedEffect(controlsVisible, remoteEnabled, playlistVisible) {
        if (controlsVisible && !remoteEnabled) {
            delay(50)
            if (!playlistVisible) runCatching { playPauseFocusRequester.requestFocus() }
        } else {
            runCatching { rootFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocusRequester.requestFocus() } }

    fun videoKey(entry: VideoManifestEntry) = "${server.host}:${server.port}/${entry.relativePath}"

    // 播放順序(存 entries 的索引)。隨機播放時一律以「指定的那部排第一,其餘打亂接在後面」重建——
    // 不能把整份清單直接洗牌,否則正在看的這部會被洗到隨機位置:洗到最後就播完即停、「下一部」不能按,
    // 「第 N 部」的顯示也會亂跳。
    var playOrder by remember { mutableStateOf(entries.indices.toList()) }
    fun buildPlayOrder(first: Int): List<Int> =
        if (shuffle) listOf(first) + (entries.indices - first).shuffled() else entries.indices.toList()

    /** 隨機播放中手動挑片:已經播過的保留在前面(「上一部」還回得去),挑的這部接在後面,
     * 剩下還沒播的重新洗牌。依序播放時順序本來就固定,不用動。 */
    fun reorderForManualPick(index: Int) {
        if (!shuffle) return
        val position = playOrder.indexOf(currentIndex)
        val history = playOrder.take(position + 1) - index
        playOrder = history + index + (entries.indices - history.toSet() - index).shuffled()
    }

    fun playIndex(index: Int) {
        if (index !in entries.indices) return
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
            val resumeAt = pendingStartPositionMs
            pendingStartPositionMs = null
            scope.launch {
                val saved = resumeAt ?: prefs.getPosition(videoKey(entry))
                if (saved != null && saved > 5_000L) exoPlayer.seekTo(saved)
            }
        }
    }

    fun previousIndex(): Int? = playOrder.getOrNull(playOrder.indexOf(currentIndex) - 1)
    fun nextIndex(): Int? = playOrder.indexOf(currentIndex).takeIf { it >= 0 }?.let { playOrder.getOrNull(it + 1) }

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

    fun setVolumeAndPersist(value: Float) {
        // 四捨五入到 0.1 的倍數,避免浮點累加後出現 0.70000005 這種值、百分比顯示跳動。
        volume = (Math.round(value.coerceIn(0f, 1f) * 10f) / 10f)
        mutedVolume = null
        exoPlayer.volume = volume
        scope.launch { prefs.setVolume(volume) }
    }

    fun toggleMute() {
        val savedVolume = mutedVolume
        if (savedVolume != null) {
            mutedVolume = null
            volume = savedVolume
        } else {
            mutedVolume = volume.takeIf { it > 0f } ?: 1f
            volume = 0f
        }
        exoPlayer.volume = volume
    }

    LaunchedEffect(Unit) {
        volume = prefs.volume.first()
        speed = prefs.speed.first()
        autoplayNext = prefs.autoplayNext.first()
        shuffle = prefs.shuffle.first()
        playOrder = buildPlayOrder(startIndex)
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
                    recentlyWatched.remove(server.displayName, finished.relativePath)
                    val next = nextIndex()
                    // 遠端控制中由主機決定要不要/切到哪一部,觀看端不自己跳下一部。
                    if (autoplayNext && !remoteEnabled && next != null) scope.launch { playIndex(next) }
                    else showControls()
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
            if (!remoteEnabled && exoPlayer.isPlaying) {
                recentlyWatched.record(
                    server.displayName, entry.relativePath, entry.name,
                    exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L),
                )
            }
        }
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // 跟手機版 VideoPlayerScreen 一樣訂閱主機推送的狀態,不用定時輪詢。
    LaunchedEffect(server) {
        observeControlState(server.host, server.port).collect { state ->
            remoteEnabled = state.enabled
            if (!state.enabled) return@collect
            playlistVisible = false

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
    // 返回鍵逐層退出:播放清單 → 控制列 → 離開畫面(跟 TV 播放器常見的行為一致)。
    BackHandler {
        when {
            remoteEnabled -> navigateBack()
            playlistVisible -> { playlistVisible = false; showControls() }
            controlsVisible && isPlaying -> controlsVisible = false
            else -> navigateBack()
        }
    }

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
                    Key.MediaNext -> { showControls(); nextIndex()?.let(::playIndex); true }
                    Key.MediaPrevious -> { showControls(); previousIndex()?.let(::playIndex); true }
                    else -> if (controlsVisible || playlistVisible) {
                        // 控制列/清單已經顯示,交給正常的焦點系統在按鈕間移動——只在這裡重置自動隱藏的
                        // 計時器,避免使用者還在操作控制列時它自己先淡出。
                        showControls()
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionLeft -> { showControls(); seekBy(-SkipDurationMs); true }
                            Key.DirectionRight -> { showControls(); seekBy(SkipDurationMs); true }
                            Key.DirectionCenter, Key.Enter -> { showControls(); togglePlayPause(); true }
                            // 返回鍵交給 BackHandler,不能在這裡吃掉。
                            Key.Back, Key.Escape -> false
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

        if (!remoteEnabled) {
            AnimatedVisibility(
                visible = controlsVisible && !playlistVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                TvVideoControls(
                    title = currentEntry?.name ?: server.displayName,
                    subtitle = buildString {
                        append(server.displayName)
                        if (entries.size > 1) append("・第 ${playOrder.indexOf(currentIndex) + 1} / ${entries.size} 部")
                    },
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    playPauseFocusRequester = playPauseFocusRequester,
                    hasPrevious = previousIndex() != null,
                    hasNext = nextIndex() != null,
                    onPrevious = { showControls(); previousIndex()?.let(::playIndex) },
                    onNext = { showControls(); nextIndex()?.let(::playIndex) },
                    onRewind = { showControls(); seekBy(-SkipDurationMs) },
                    onForward = { showControls(); seekBy(SkipDurationMs) },
                    onSeek = { deltaMs -> showControls(); seekBy(deltaMs) },
                    onPlayPause = { showControls(); togglePlayPause() },
                    volume = volume,
                    onVolumeStep = { delta -> showControls(); setVolumeAndPersist(volume + delta) },
                    onToggleMute = { showControls(); toggleMute() },
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
                    },
                    shuffle = shuffle,
                    onToggleShuffle = {
                        showControls()
                        shuffle = !shuffle
                        playOrder = buildPlayOrder(currentIndex)
                        scope.launch { prefs.setShuffle(shuffle) }
                    },
                    showPlaylistButton = entries.size > 1,
                    onOpenPlaylist = { showControls(); playlistVisible = true },
                )
            }

            AnimatedVisibility(
                visible = playlistVisible,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            ) {
                TvPlaylistPanel(
                    entries = entries,
                    currentIndex = currentIndex,
                    server = server,
                    sortOption = sortOption,
                    onCycleSort = {
                        sortOption = VideoSortOption.entries[(sortOption.ordinal + 1) % VideoSortOption.entries.size]
                    },
                    onSelect = { index ->
                        playlistVisible = false
                        if (index != currentIndex) {
                            reorderForManualPick(index)
                            playIndex(index)
                        }
                        showControls()
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
    subtitle: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playPauseFocusRequester: FocusRequester,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    volume: Float,
    onVolumeStep: (Float) -> Unit,
    onToggleMute: () -> Unit,
    speed: Float,
    onCycleSpeed: () -> Unit,
    autoplayNext: Boolean,
    onToggleAutoplay: () -> Unit,
    shuffle: Boolean,
    onToggleShuffle: () -> Unit,
    showPlaylistButton: Boolean,
    onOpenPlaylist: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f)))
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
        )

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
            IconButton(onClick = onPlayPause, modifier = Modifier.focusRequester(playPauseFocusRequester)) {
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
                    if (volume <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "靜音",
                )
            }
            TvVolumeBar(volume = volume, onStep = onVolumeStep, modifier = Modifier.width(120.dp))
            Text(
                "${Math.round(volume * 100)}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(44.dp),
            )
            ToggleIconButton(
                active = autoplayNext,
                onClick = onToggleAutoplay,
                icon = if (autoplayNext) Icons.Filled.Repeat else Icons.Filled.RepeatOne,
                contentDescription = if (autoplayNext) "自動播放下一部:開" else "自動播放下一部:關",
            )
            ToggleIconButton(
                active = shuffle,
                onClick = onToggleShuffle,
                icon = Icons.Filled.Shuffle,
                contentDescription = if (shuffle) "隨機播放:開" else "隨機播放:關",
            )
            OutlinedButton(onClick = onCycleSpeed) {
                Icon(Icons.Filled.Speed, contentDescription = null)
                Text(" ${formatSpeedLabel(speed)}")
            }
            if (showPlaylistButton) {
                IconButton(onClick = onOpenPlaylist) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "播放清單")
                }
            }
        }
    }
}

/** 開/關狀態的圖示按鈕:開啟時套品牌色底,關閉時是一般的半透明按鈕,10 呎外也看得出狀態。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleIconButton(
    active: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    IconButton(
        onClick = onClick,
        colors = if (active) {
            IconButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                contentColor = Color.White,
            )
        } else {
            IconButtonDefaults.colors(contentColor = Color.White.copy(alpha = 0.6f))
        },
    ) {
        Icon(icon, contentDescription = contentDescription)
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
    TvFocusableBar(
        fraction = tvFractionOf(positionMs, durationMs),
        onLeft = { onSeek(-SkipDurationMs) },
        onRight = { onSeek(SkipDurationMs) },
        modifier = modifier,
    )
}

/**
 * 音量條:左右兩側都有按鈕,如果 focus 到就直接用左右鍵調音量,焦點會被困在這裡。所以平常左右鍵
 * 只負責移動焦點,按確認鍵才進入「調整模式」(條變粗、顯示提示),這時左右鍵以 10% 為單位調整;
 * 再按一次確認鍵、返回鍵或焦點離開就結束調整。
 */
@Composable
private fun TvVolumeBar(volume: Float, onStep: (Float) -> Unit, modifier: Modifier = Modifier) {
    var adjusting by remember { mutableStateOf(false) }
    Column(modifier) {
        TvFocusableBar(
            fraction = volume.coerceIn(0f, 1f),
            onLeft = { onStep(-VolumeStep) },
            onRight = { onStep(VolumeStep) },
            arrowsEnabled = adjusting,
            highlighted = adjusting,
            onFocusChanged = { focused -> if (!focused) adjusting = false },
            onExtraKey = { key ->
                when (key) {
                    Key.DirectionCenter, Key.Enter -> { adjusting = !adjusting; true }
                    Key.Back, Key.Escape -> if (adjusting) { adjusting = false; true } else false
                    else -> false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (adjusting) "← → 調整・確認結束" else "按確認調整",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * [arrowsEnabled] 為 false 時不處理左右鍵,交給焦點系統移到相鄰元件;[onExtraKey] 處理左右鍵以外的
 * 按鍵,回傳 true 代表吃掉。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvFocusableBar(
    fraction: Float,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
    arrowsEnabled: Boolean = true,
    highlighted: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    onExtraKey: (Key) -> Boolean = { false },
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChanged(focused) }

    Box(
        modifier = modifier
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> if (arrowsEnabled) { onLeft(); true } else false
                    Key.DirectionRight -> if (arrowsEnabled) { onRight(); true } else false
                    else -> onExtraKey(event.key)
                }
            }
            .height(if (highlighted) 14.dp else if (focused) 10.dp else 6.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.25f))
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (highlighted) Color.White else MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(50))
                .background(BrandColors.GradientHorizontal),
        )
    }
}

/**
 * 從右側滑出的播放清單,對應手機版的側邊/堆疊清單:可切換排序(只影響清單顯示順序,上一部/
 * 下一部仍照原本順序或隨機順序走),打開時焦點直接落在目前播放的那一部上。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPlaylistPanel(
    entries: List<VideoManifestEntry>,
    currentIndex: Int,
    server: DiscoveredDevice,
    sortOption: VideoSortOption,
    onCycleSort: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val indexByPath = remember(entries) { entries.withIndex().associate { (index, entry) -> entry.relativePath to index } }
    val sortedEntries = remember(entries, sortOption) { entries.sortedByOption(sortOption) }
    val currentPosition = sortedEntries.indexOfFirst { indexByPath[it.relativePath] == currentIndex }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentPosition)
    val currentFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { currentFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("播放清單", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCycleSort) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                Text(" ${sortOption.label}", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sortedEntries, key = { it.relativePath }) { entry ->
                val index = indexByPath.getValue(entry.relativePath)
                val isCurrent = index == currentIndex
                Card(
                    onClick = { onSelect(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isCurrent) Modifier.focusRequester(currentFocusRequester) else Modifier),
                    border = tvBrandCardBorder(),
                    scale = tvBrandCardScale(),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.width(140.dp)) {
                            VideoThumbnail(
                                url = videoThumbnailUrl(server.host, server.port, entry.relativePath),
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 10.dp,
                            )
                            if (isCurrent) NowPlayingBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatBytes(entry.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

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
