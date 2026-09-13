package com.connectit.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.components.ConnectionRequestDialog
import com.connectit.android.ui.screens.ConnectedScreen
import com.connectit.android.ui.screens.DevicesScreen
import com.connectit.android.ui.screens.SettingsScreen
import com.connectit.android.ui.screens.VideoPlayerScreen
import com.connectit.android.ui.screens.VideoScreen
import com.connectit.android.ui.screens.VideoServerScreen

private enum class AppTab(val label: String) { DEVICES("裝置"), VIDEO("影片"), SETTINGS("設定") }

/** 「影片」頁的子導覽:選伺服器 -> 看清單(向對方要 manifest)-> 播放。 */
private sealed interface VideoNav {
    data object Root : VideoNav
    data class Manifest(val server: DiscoveredDevice) : VideoNav
    data class Player(val server: DiscoveredDevice, val entries: List<VideoManifestEntry>, val startIndex: Int) : VideoNav
}

@Composable
fun ConnectItApp(service: ConnectItService) {
    var tab by remember { mutableStateOf(AppTab.DEVICES) }
    var videoNav by remember { mutableStateOf<VideoNav>(VideoNav.Root) }
    val snackbarHostState = remember { SnackbarHostState() }

    val connectionState by service.connectionState.collectAsState()
    val pendingConnectionRequest by service.pendingConnectionRequest.collectAsState()

    LaunchedEffect(service) {
        service.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    when (val nav = videoNav) {
        is VideoNav.Manifest -> {
            VideoServerScreen(
                server = nav.server,
                onBack = { videoNav = VideoNav.Root },
                onPlay = { entries, startIndex -> videoNav = VideoNav.Player(nav.server, entries, startIndex) },
            )
            return
        }
        is VideoNav.Player -> {
            VideoPlayerScreen(
                server = nav.server,
                entries = nav.entries,
                startIndex = nav.startIndex,
                onBack = { videoNav = VideoNav.Manifest(nav.server) },
            )
            return
        }
        VideoNav.Root -> Unit
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.DEVICES,
                    onClick = { tab = AppTab.DEVICES },
                    icon = { Icon(Icons.Filled.Devices, contentDescription = null) },
                    label = { Text(AppTab.DEVICES.label) },
                )
                NavigationBarItem(
                    selected = tab == AppTab.VIDEO,
                    onClick = { tab = AppTab.VIDEO },
                    icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
                    label = { Text(AppTab.VIDEO.label) },
                )
                NavigationBarItem(
                    selected = tab == AppTab.SETTINGS,
                    onClick = { tab = AppTab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(AppTab.SETTINGS.label) },
                )
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (tab) {
            AppTab.DEVICES -> {
                val connected = connectionState
                if (connected is ConnectionUiState.Connected) {
                    ConnectedScreen(service = service, peer = connected.peer, modifier = contentModifier)
                } else {
                    DevicesScreen(service = service, modifier = contentModifier)
                }
            }
            AppTab.VIDEO -> VideoScreen(
                service = service,
                modifier = contentModifier,
                onWatch = { server -> videoNav = VideoNav.Manifest(server) },
            )
            AppTab.SETTINGS -> SettingsScreen(service = service, modifier = contentModifier)
        }
    }

    pendingConnectionRequest?.let { request ->
        ConnectionRequestDialog(
            request = request,
            onRespond = { accept -> service.respondToConnectionRequest(accept) },
        )
    }
}
