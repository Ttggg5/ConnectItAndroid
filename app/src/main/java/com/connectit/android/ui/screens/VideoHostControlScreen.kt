package com.connectit.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.connectit.android.net.videoThumbnailUrl
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.VideoHostUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val SpeedOptions = listOf(0.5, 1.0, 1.25, 1.5, 2.0)

/**
 * 主機端的「遙控器」畫面:開/關遠端控制模式、從自己分享的影片庫選片、播放/暫停、拖曳進度。
 * 主機本身不需要在這裡播放影片——這裡下的每個指令都直接寫進 [ConnectItService] 持有的
 * `PlaybackControlState`,再由觀眾端(見 VideoPlayerScreen.kt / Windows 端觀看頁)輪詢套用。
 */
@Composable
fun VideoHostControlScreen(service: ConnectItService, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val hostState by service.videoHostState.collectAsState()
    var snapshot by remember { mutableStateOf(service.currentPlaybackSnapshot()) }
    var durationMs by remember { mutableStateOf<Long?>(null) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewFraction by remember { mutableStateOf(0f) }

    // 用本機輪詢(不是網路請求,跟觀眾端的 HTTP 輪詢不同)持續更新進度條,讓「正在播放」時
    // 進度看起來會自己走動,而不是要等下一次下指令才刷新。
    LaunchedEffect(Unit) {
        while (isActive) {
            snapshot = service.currentPlaybackSnapshot()
            delay(500)
        }
    }

    // 主機平常完全不解析媒體檔案,唯獨時間軸需要知道總長度才能畫,所以只在選片改變時才探測一次
    // (見 VideoDurationProbe),探測結果由 VideoHostServer 快取,同一支影片不會重複探測。
    LaunchedEffect(snapshot.videoRelativePath) {
        durationMs = snapshot.videoRelativePath?.let { service.hostVideoDurationMs(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("遙控模式") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val running = hostState as? VideoHostUiState.Running
        if (running == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("尚未開始分享影片,請先在「影片」頁選擇資料夾分享。")
            }
            return@Scaffold
        }

        val entries = service.hostManifestEntries()
        val currentEntry = entries.firstOrNull { it.relativePath == snapshot.videoRelativePath }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("遠端控制模式", style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = snapshot.enabled,
                            onCheckedChange = {
                                service.setRemoteControlEnabled(it)
                                snapshot = service.currentPlaybackSnapshot()
                            },
                        )
                    }
                    Text(
                        if (snapshot.enabled) {
                            "開啟中:所有觀看端會跟著這裡的播放/暫停/進度/選片同步,無法自行操作。"
                        } else {
                            "關閉時,觀看端維持原本各自自由觀看的行為。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (snapshot.enabled) {
                        Text(currentEntry?.name ?: "尚未選擇影片", style = MaterialTheme.typography.bodyMedium)

                        val duration = durationMs
                        Slider(
                            value = if (isSeeking) seekPreviewFraction else fractionOf(snapshot.positionMs, duration ?: 0L),
                            onValueChange = {
                                isSeeking = true
                                seekPreviewFraction = it
                            },
                            onValueChangeFinished = {
                                if (duration != null && duration > 0) {
                                    service.remoteControlSeek((seekPreviewFraction * duration).toLong())
                                    snapshot = service.currentPlaybackSnapshot()
                                }
                                isSeeking = false
                            },
                            enabled = duration != null && duration > 0,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            formatMs(if (isSeeking && duration != null) (seekPreviewFraction * duration).toLong() else snapshot.positionMs) +
                                (duration?.let { " / " + formatMs(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )

                        val currentEntryIndex = entries.indexOfFirst { it.relativePath == snapshot.videoRelativePath }
                        val hasPrevious = currentEntryIndex > 0
                        val hasNext = currentEntryIndex in 0 until entries.size - 1

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { service.remoteControlSkip(-1); snapshot = service.currentPlaybackSnapshot() }, enabled = hasPrevious) {
                                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一部")
                            }
                            IconButton(
                                onClick = {
                                    service.remoteControlSeek((snapshot.positionMs - 10_000L).coerceAtLeast(0L))
                                    snapshot = service.currentPlaybackSnapshot()
                                },
                                enabled = currentEntry != null,
                            ) {
                                Icon(Icons.Filled.FastRewind, contentDescription = "倒退 10 秒")
                            }
                            IconButton(
                                onClick = {
                                    service.remoteControlSetPlaying(!snapshot.isPlaying)
                                    snapshot = service.currentPlaybackSnapshot()
                                },
                                enabled = currentEntry != null,
                            ) {
                                Icon(
                                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "播放/暫停",
                                )
                            }
                            IconButton(
                                onClick = {
                                    service.remoteControlSeek(snapshot.positionMs + 10_000L)
                                    snapshot = service.currentPlaybackSnapshot()
                                },
                                enabled = currentEntry != null,
                            ) {
                                Icon(Icons.Filled.FastForward, contentDescription = "快轉 10 秒")
                            }
                            IconButton(onClick = { service.remoteControlSkip(1); snapshot = service.currentPlaybackSnapshot() }, enabled = hasNext) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "下一部")
                            }

                            var speedMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { speedMenuExpanded = true }) {
                                    Icon(Icons.Filled.Speed, contentDescription = "播放速度")
                                }
                                DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                                    SpeedOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text("${option}x") },
                                            onClick = {
                                                service.remoteControlSetPlaybackRate(option)
                                                snapshot = service.currentPlaybackSnapshot()
                                                speedMenuExpanded = false
                                            },
                                            leadingIcon = if (option == snapshot.playbackRate) {
                                                { Icon(Icons.Filled.Check, contentDescription = null) }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    service.remoteControlSetMuted(!snapshot.muted)
                                    snapshot = service.currentPlaybackSnapshot()
                                },
                            ) {
                                Icon(
                                    if (snapshot.muted || snapshot.volume <= 0.0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "靜音",
                                )
                            }
                            Slider(
                                value = if (snapshot.muted) 0f else snapshot.volume.toFloat(),
                                onValueChange = {
                                    service.remoteControlSetVolume(it.toDouble())
                                    if (it > 0f && snapshot.muted) service.remoteControlSetMuted(false)
                                    snapshot = service.currentPlaybackSnapshot()
                                },
                                modifier = Modifier.width(160.dp),
                            )
                        }
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 200.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(entries, key = { it.relativePath }) { entry ->
                    val isCurrent = entry.relativePath == snapshot.videoRelativePath
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isCurrent) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            CardDefaults.cardColors()
                        },
                        onClick = {
                            service.remoteControlPlayVideo(entry.relativePath)
                            snapshot = service.currentPlaybackSnapshot()
                        },
                    ) {
                        Column {
                            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                AsyncImage(
                                    model = videoThumbnailUrl("127.0.0.1", running.port, entry.relativePath),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                Icon(
                                    Icons.Filled.PlayCircle,
                                    contentDescription = "選擇",
                                    modifier = Modifier.align(Alignment.Center),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                if (isCurrent) {
                                    Text("目前選片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fractionOf(position: Long, duration: Long): Float =
    if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
