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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
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
import com.connectit.android.net.observeControlState
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
    /** 遠端控制模式中返回時要去的地方(app 的「影片」主頁籤),不是回到這個主機的清單/瀏覽畫面
     * (見 [onBack])——遠端控制模式下不該讓觀眾回得去那個瀏覽畫面,道理跟不該顯示網站首頁清單一樣。 */
    onExitRemoteControl: () -> Unit,
    /** 進入畫面前(來自 manifest)對「主機是否開了遠端控制模式」的初步提示,只用來避免掛載瞬間
     * 先閃一下自由控制列——真正即時、會持續更新的判斷來自下面訂閱的主機推送([observeControlState])。 */
    remoteControlHint: Boolean = false,
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
    var shuffle by remember { mutableStateOf(false) }
    // 每次打開隨機播放就遞增一次,當作 playOrder 的 remember key 之一——單靠 shuffle 這個布林值
    // 沒辦法讓「關掉再打開」重新洗一次牌(同一個 true 值不會觸發 remember 重算)。
    var shuffleSeed by remember { mutableStateOf(0) }
    var volume by remember { mutableStateOf(1f) }
    var mutedVolume by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableStateOf(1f) }
    var isFullscreen by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf<Int?>(null) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    // 觀看頁清單自己的顯示排序,只影響清單怎麼列出來,不影響上一部/下一部、自動播放下一部的順序
    // ——那些仍照 entries 原本的順序走(進這個畫面之前,在 VideoServerScreen 選好、固定下來的順序)。
    var sortOption by remember { mutableStateOf(VideoSortOption.NAME_ASC) }

    // 遠端控制模式:主機正在控制播放時鎖住手動控制列,改成跟隨主機推送的狀態播放(見下方
    // observeControlState 的訂閱)。remoteControlHint 只是掛載時的初始值,實際狀態一律以
    // 主機推送的結果為準,主機隨時可能開關這個模式。
    var remoteEnabled by remember { mutableStateOf(remoteControlHint) }
    var lastRemoteResyncAt by remember { mutableStateOf(0L) }

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
        // 遠端控制模式下,播放位置由主機的狀態決定(見輪詢迴圈),不要用這個使用者自己
        // 上次留下的記錄蓋掉它。
        if (!remoteEnabled) {
            scope.launch {
                val saved = prefs.getPosition(videoKey(entry))
                if (saved != null && saved > 5_000L) {
                    exoPlayer.seekTo(saved)
                }
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

    // 初始套用上次記住的音量/速度/自動播放/隨機播放設定,並播放使用者點的那一部。
    LaunchedEffect(Unit) {
        volume = prefs.volume.first()
        speed = prefs.speed.first()
        autoplayNext = prefs.autoplayNext.first()
        shuffle = prefs.shuffle.first()
        exoPlayer.volume = volume
        exoPlayer.setPlaybackSpeed(speed)
        playIndex(startIndex)
    }

    // 隨機播放開啟時,上一部/下一部/播完自動播放下一部改走洗牌過的順序;關閉時仍照 entries
    // 原本順序走(跟這個畫面本來的行為一致)。playOrder 存的是 entries 的索引,不是 entries 本身。
    // 用 mutableStateOf(而不是單純的 val remember)是因為下面 onPlaybackStateChanged 的監聽器
    // 只在 exoPlayer 建立時註冊一次,之後要讀到「當下」的 playOrder(而不是註冊當下那份舊值),
    // 就得靠 State 讀取,跟 currentIndex/autoplayNext 已經在用的做法一致。
    var playOrder by remember { mutableStateOf(entries.indices.toList()) }
    LaunchedEffect(shuffle, shuffleSeed, entries) {
        playOrder = if (shuffle) entries.indices.shuffled() else entries.indices.toList()
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
                    val position = playOrder.indexOf(currentIndex)
                    val nextIndex = playOrder.getOrNull(position + 1)
                    if (autoplayNext && nextIndex != null) {
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

    // 遠端控制模式:不管目前 remoteEnabled 是不是 true,都持續訂閱主機推送的狀態——主機一有
    // 變更(播放/暫停/拖曳進度/換片/關掉遙控模式)就立刻推過來,不用這裡定時去問;沒有變更時
    // 主機也會定期送心跳狀態,順便校正播放器累積的漂移。
    LaunchedEffect(server) {
        observeControlState(server.host, server.port).collect { state ->
            remoteEnabled = state.enabled
            if (!state.enabled) return@collect

            val targetIndex = state.videoRelativePath
                ?.let { path -> entries.indexOfFirst { it.relativePath == path } }
                ?.takeIf { it >= 0 }
            if (targetIndex != null && targetIndex != currentIndex) {
                playIndex(targetIndex)
            }

            if (state.isPlaying && !exoPlayer.isPlaying) {
                exoPlayer.play()
            } else if (!state.isPlaying && exoPlayer.isPlaying) {
                exoPlayer.pause()
            }

            val cooldownActive = System.currentTimeMillis() - lastRemoteResyncAt < 1_000L
            if (!cooldownActive && kotlin.math.abs(exoPlayer.currentPosition - state.positionMs) > 1_500L) {
                val duration = exoPlayer.duration
                val target = if (duration > 0) state.positionMs.coerceIn(0L, duration) else state.positionMs.coerceAtLeast(0L)
                exoPlayer.seekTo(target)
                lastRemoteResyncAt = System.currentTimeMillis()
            }

            // 遠端控制中,音量/靜音/播放速度也一併跟主機同步(控制列被鎖住了,觀眾本來
            // 就不能自己調),跟網頁版 applyRemoteState 的做法一致。
            if (exoPlayer.playbackParameters.speed != state.playbackRate.toFloat()) {
                exoPlayer.setPlaybackSpeed(state.playbackRate.toFloat())
            }
            val targetVolume = if (state.muted) 0f else state.volume.toFloat()
            if (kotlin.math.abs(exoPlayer.volume - targetVolume) > 0.001f) {
                exoPlayer.volume = targetVolume
            }
        }
    }

    // 進入/離開遠端控制模式時自動切換全螢幕(對應網頁版在同一時機呼叫 requestFullscreen)——
    // 只在 remoteEnabled 真的改變時觸發一次,不會每次輪詢都重新蓋掉使用者之後手動切回/切出
    // 全螢幕的選擇(例如遠端模式中使用者按返回鍵退出全螢幕,下一輪輪詢不會又把它拉回全螢幕)。
    LaunchedEffect(remoteEnabled) {
        applyFullscreen(remoteEnabled)
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

    fun navigateBack() {
        if (remoteEnabled) onExitRemoteControl() else onBack()
    }

    BackHandler {
        when {
            // 遠端控制模式中沒有任何可操作的控制列可以留在原地用,離開全螢幕沒有意義,
            // 所以不管目前是不是全螢幕,退出的動作一律直接送回 app 的影片頁,不用先跳出
            // 全螢幕、再按一次才真的離開這兩步(一般非遠端模式仍維持原本兩步的行為)。
            remoteEnabled -> navigateBack()
            isFullscreen -> applyFullscreen(false)
            else -> navigateBack()
        }
    }

    val currentEntry = entries.getOrNull(currentIndex)
    val orderPosition = playOrder.indexOf(currentIndex)

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text(currentEntry?.name ?: server.displayName, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = ::navigateBack) {
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
            // 折疊機攤開、平板橫向這種寬螢幕才把清單放在側邊(對應 Windows 觀看頁
            // `main.watch{grid-template-columns:minmax(0,1fr) 360px}` 的側邊清單)。
            val showSidebar = !isFullscreen && maxWidth >= AdaptiveNavigationBreakpoint && entries.size > 1
            // 手機直向這種窄螢幕擠不下側邊欄,但清單不能因此整個消失不見——對應 Windows 觀看頁窄視窗時
            // `@media(max-width:860px){main.watch{grid-template-columns:minmax(0,1fr)}}` 讓清單從
            // 側邊改成疊到播放器下面、繼續往下捲動就看得到的做法。全螢幕時仍然不顯示,理由跟側邊欄一樣。
            val showStackedList = !isFullscreen && !showSidebar && entries.size > 1

            val playerContent: @Composable () -> Unit = {
                Column(modifier = if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
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
                            visible = !remoteEnabled && (controlsVisible || !isPlaying),
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
                                hasPrevious = orderPosition > 0,
                                hasNext = orderPosition in 0 until playOrder.lastIndex,
                                onPrevious = { showControls(); playIndex(playOrder[orderPosition - 1]) },
                                onNext = { showControls(); playIndex(playOrder[orderPosition + 1]) },
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
                                shuffle = shuffle,
                                onShuffleChange = { checked ->
                                    showControls()
                                    shuffle = checked
                                    if (checked) shuffleSeed++
                                    scope.launch { prefs.setShuffle(checked) }
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

            when {
                showSidebar -> {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { playerContent() }
                        VideoSidebar(
                            entries = entries,
                            currentIndex = currentIndex,
                            server = server,
                            sortOption = sortOption,
                            onSortOptionChange = { sortOption = it },
                            onSelect = { index -> playIndex(index) },
                            modifier = Modifier.width(320.dp).fillMaxHeight(),
                        )
                    }
                }
                showStackedList -> {
                    // 播放器/標題只是清單最上面那一項,跟其他影片列一起放進同一個 LazyColumn,
                    // 才能整頁一起往下捲動(而不是把會自撐高度的 LazyColumn 塞進另一個可捲動容器裡)。
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { playerContent() }
                        videoPlaylistItems(
                            entries = entries,
                            currentIndex = currentIndex,
                            server = server,
                            sortOption = sortOption,
                            onSortOptionChange = { sortOption = it },
                            onSelect = { index -> playIndex(index) },
                            rowModifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                else -> playerContent()
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
    shuffle: Boolean,
    onShuffleChange: (Boolean) -> Unit,
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onShuffleChange(!shuffle) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("隨機播放")
                            Checkbox(checked = shuffle, onCheckedChange = onShuffleChange)
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
    sortOption: VideoSortOption,
    onSortOptionChange: (VideoSortOption) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        videoPlaylistItems(
            entries = entries,
            currentIndex = currentIndex,
            server = server,
            sortOption = sortOption,
            onSortOptionChange = onSortOptionChange,
            onSelect = onSelect,
        )
    }
}

/** 側邊清單、手機直向堆疊清單共用的內容:排序選單 + 依排序後順序列出的影片列——用
 * [LazyListScope] 的擴充函式而不是包一層自己的 Composable,是因為兩邊各自是不同的 LazyColumn
 * (側邊欄自己一個、堆疊清單是跟播放器同一個),沒辦法共用同一個 LazyColumn 實例。
 *
 * `entry.relativePath` 拿來當排序後找回原始索引的鍵——[onSelect]、`isCurrent` 都要對照
 * [entries] 原本(未排序)的索引,因為上一部/下一部、自動播放下一部全部照 [entries] 原本順序走,
 * 不會因為這個清單改了顯示排序就跟著變。 */
private fun LazyListScope.videoPlaylistItems(
    entries: List<VideoManifestEntry>,
    currentIndex: Int,
    server: DiscoveredDevice,
    sortOption: VideoSortOption,
    onSortOptionChange: (VideoSortOption) -> Unit,
    onSelect: (Int) -> Unit,
    rowModifier: Modifier = Modifier,
) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            SortMenuButton(current = sortOption, onSelect = onSortOptionChange)
        }
    }

    val indexByPath = entries.withIndex().associate { (index, entry) -> entry.relativePath to index }
    val sortedEntries = entries.sortedByOption(sortOption)
    items(sortedEntries, key = { it.relativePath }) { entry ->
        val index = indexByPath.getValue(entry.relativePath)
        VideoListRow(
            entry = entry,
            isCurrent = index == currentIndex,
            server = server,
            onClick = { onSelect(index) },
            modifier = rowModifier,
        )
    }
}

@Composable
private fun SortMenuButton(current: VideoSortOption, onSelect: (VideoSortOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序方式")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VideoSortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    leadingIcon = if (option == current) {
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
private fun VideoListRow(
    entry: VideoManifestEntry,
    isCurrent: Boolean,
    server: DiscoveredDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(enabled = !isCurrent, onClick = onClick)
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
