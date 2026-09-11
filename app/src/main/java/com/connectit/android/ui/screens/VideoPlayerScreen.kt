package com.connectit.android.ui.screens

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.connectit.android.R
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.net.videoMediaUrl

/**
 * 用 ExoPlayer(Media3)原生播放,取代先前用 WebView 播放 `<video>` 的做法——後者內嵌在
 * Compose 的 AndroidView 裡有已知的相容性問題(聲音正常但畫面全黑,在真機上也重現)。
 * 影片清單一次全部排進播放清單,ExoPlayer 內建的控制列會自動出現上一部/下一部按鈕。
 */
@Composable
fun VideoPlayerScreen(
    server: DiscoveredDevice,
    entries: List<VideoManifestEntry>,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    val exoPlayer = remember(server, entries) {
        ExoPlayer.Builder(context).build().apply {
            entries.forEach { entry ->
                val mediaItem = MediaItem.Builder()
                    .setUri(videoMediaUrl(server.host, server.port, entry.relativePath))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.name).build())
                    .build()
                addMediaItem(mediaItem)
            }
            seekTo(startIndex, 0)
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entries.getOrNull(startIndex)?.name ?: server.displayName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_video_player, null) as PlayerView).apply {
                    player = exoPlayer
                }
            },
        )
    }
}
