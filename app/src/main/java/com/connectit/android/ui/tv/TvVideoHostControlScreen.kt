package com.connectit.android.ui.tv

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val SpeedOptions = listOf(0.5, 1.0, 1.25, 1.5, 2.0)

/**
 * TV 版遙控模式主控畫面:跟手機版 VideoHostControlScreen 一樣直接寫進 [ConnectItService] 持有的
 * PlaybackControlState,由觀眾端輪詢套用(見 VideoPlayerScreen.kt / TvVideoPlayerScreen.kt)。
 * 進度/音量改用「±10 秒/±10%」按鈕而不是可拖曳的 Slider——遙控器上用方向鍵精準拖動進度條很不
 * 好操作,一組固定間距的按鈕反而更可靠。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvVideoHostControlScreen(service: ConnectItService, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val hostState by service.videoHostState.collectAsState()
    var snapshot by remember { mutableStateOf(service.currentPlaybackSnapshot()) }
    var durationMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        while (isActive) {
            snapshot = service.currentPlaybackSnapshot()
            delay(500)
        }
    }

    LaunchedEffect(snapshot.videoRelativePath) {
        durationMs = snapshot.videoRelativePath?.let { service.hostVideoDurationMs(it) }
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
            Text("遙控模式", style = MaterialTheme.typography.headlineSmall)
        }

        val running = hostState as? VideoHostUiState.Running
        if (running == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("尚未開始分享影片,請先在「影片」頁選擇資料夾分享。")
            }
            return
        }

        val entries = service.hostManifestEntries()
        val currentEntry = entries.firstOrNull { it.relativePath == snapshot.videoRelativePath }

        Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                    LinearProgressIndicator(
                        progress = { fractionOf(snapshot.positionMs, duration ?: 0L) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        formatMs(snapshot.positionMs) + (duration?.let { " / " + formatMs(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    val currentEntryIndex = entries.indexOfFirst { it.relativePath == snapshot.videoRelativePath }
                    val hasPrevious = currentEntryIndex > 0
                    val hasNext = currentEntryIndex in 0 until entries.size - 1

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { service.remoteControlSkip(-1); snapshot = service.currentPlaybackSnapshot() },
                            enabled = hasPrevious,
                        ) { Icon(Icons.Filled.SkipPrevious, contentDescription = "上一部") }
                        IconButton(
                            onClick = {
                                service.remoteControlSeek((snapshot.positionMs - 10_000L).coerceAtLeast(0L))
                                snapshot = service.currentPlaybackSnapshot()
                            },
                            enabled = currentEntry != null,
                        ) { Icon(Icons.Filled.FastRewind, contentDescription = "倒退 10 秒") }
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
                        ) { Icon(Icons.Filled.FastForward, contentDescription = "快轉 10 秒") }
                        IconButton(
                            onClick = { service.remoteControlSkip(1); snapshot = service.currentPlaybackSnapshot() },
                            enabled = hasNext,
                        ) { Icon(Icons.Filled.SkipNext, contentDescription = "下一部") }

                        IconButton(onClick = {
                            service.remoteControlSetMuted(!snapshot.muted)
                            snapshot = service.currentPlaybackSnapshot()
                        }) {
                            Icon(
                                if (snapshot.muted || snapshot.volume <= 0.0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "靜音",
                            )
                        }
                        IconButton(onClick = {
                            service.remoteControlSetVolume((snapshot.volume - 0.1).coerceAtLeast(0.0))
                            snapshot = service.currentPlaybackSnapshot()
                        }) { Icon(Icons.Filled.Remove, contentDescription = "降低音量") }
                        IconButton(onClick = {
                            service.remoteControlSetVolume((snapshot.volume + 0.1).coerceAtMost(1.0))
                            if (snapshot.muted) service.remoteControlSetMuted(false)
                            snapshot = service.currentPlaybackSnapshot()
                        }) { Icon(Icons.Filled.Add, contentDescription = "提高音量") }

                        IconButton(onClick = {
                            val currentIndex = SpeedOptions.indexOf(snapshot.playbackRate).let { if (it < 0) 0 else it }
                            val next = SpeedOptions[(currentIndex + 1) % SpeedOptions.size]
                            service.remoteControlSetPlaybackRate(next)
                            snapshot = service.currentPlaybackSnapshot()
                        }) { Icon(Icons.Filled.Speed, contentDescription = "播放速度 ${snapshot.playbackRate}x") }
                    }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(24.dp),
        ) {
            items(entries, key = { it.relativePath }) { entry ->
                val isCurrent = entry.relativePath == snapshot.videoRelativePath
                Card(
                    onClick = {
                        service.remoteControlPlayVideo(entry.relativePath)
                        snapshot = service.currentPlaybackSnapshot()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                            AsyncImage(
                                model = videoThumbnailUrl("127.0.0.1", running.port, entry.relativePath),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            Icon(Icons.Filled.PlayCircle, contentDescription = "選擇", modifier = Modifier.align(Alignment.Center))
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

private fun fractionOf(position: Long, duration: Long): Float =
    if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
