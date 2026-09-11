package com.connectit.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.connectit.android.net.fetchVideoManifest
import com.connectit.android.net.videoThumbnailUrl

private sealed interface ManifestState {
    data object Loading : ManifestState
    data object Failed : ManifestState
    data class Loaded(val response: VideoManifestResponse) : ManifestState
}

@Composable
fun VideoServerScreen(
    server: DiscoveredDevice,
    onBack: () -> Unit,
    onPlay: (entries: List<VideoManifestEntry>, startIndex: Int) -> Unit,
) {
    var state by remember(server) { mutableStateOf<ManifestState>(ManifestState.Loading) }

    BackHandler(onBack = onBack)

    LaunchedEffect(server) {
        val response = fetchVideoManifest(server.host, server.port)
        state = if (response != null) ManifestState.Loaded(response) else ManifestState.Failed
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            is ManifestState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ManifestState.Failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("無法取得影片清單,請確認對方裝置仍在線上。")
            }
            is ManifestState.Loaded -> {
                val entries = current.response.entries.sortedBy { it.name.lowercase() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(entries, key = { it.relativePath }) { entry ->
                        val index = entries.indexOf(entry)
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { onPlay(entries, index) }) {
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
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
