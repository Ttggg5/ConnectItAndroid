package com.connectit.android.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.unit.dp
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.components.AdaptiveNavigationBreakpoint
import com.connectit.android.ui.components.ConnectionRequestDialog
import com.connectit.android.ui.screens.ConnectedScreen
import com.connectit.android.ui.screens.DevicesScreen
import com.connectit.android.ui.screens.SettingsScreen
import com.connectit.android.ui.screens.VideoHostControlScreen
import com.connectit.android.ui.screens.VideoPlayerScreen
import com.connectit.android.ui.screens.VideoScreen
import com.connectit.android.ui.screens.VideoServerScreen

private enum class AppTab(val label: String) { DEVICES("裝置"), VIDEO("影片"), SETTINGS("設定") }

/** Material 預設的 NavigationRail 只有 80dp 寬,圖示和文字標籤會擠在一起;加寬一點比較好按、好讀。 */
private val RailWidth = 120.dp

/** 側邊導覽列變寬後,預設 24dp 的圖示顯得太小,加大一點跟加寬的欄位比例更協調。 */
private val RailIconSize = 32.dp

/** 「影片」頁的子導覽:選伺服器 -> 看清單(向對方要 manifest)-> 播放。 */
private sealed interface VideoNav {
    data object Root : VideoNav
    data object HostControl : VideoNav
    data class Manifest(val server: DiscoveredDevice) : VideoNav
    data class Player(
        val server: DiscoveredDevice,
        val entries: List<VideoManifestEntry>,
        val startIndex: Int,
        val remoteControlHint: Boolean,
    ) : VideoNav
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
        VideoNav.HostControl -> {
            VideoHostControlScreen(
                service = service,
                onBack = { videoNav = VideoNav.Root },
            )
            return
        }
        is VideoNav.Manifest -> {
            VideoServerScreen(
                server = nav.server,
                onBack = { videoNav = VideoNav.Root },
                onPlay = { entries, startIndex, remoteControlEnabled ->
                    videoNav = VideoNav.Player(nav.server, entries, startIndex, remoteControlEnabled)
                },
            )
            return
        }
        is VideoNav.Player -> {
            VideoPlayerScreen(
                server = nav.server,
                entries = nav.entries,
                startIndex = nav.startIndex,
                remoteControlHint = nav.remoteControlHint,
                onBack = { videoNav = VideoNav.Manifest(nav.server) },
                onExitRemoteControl = { videoNav = VideoNav.Root },
            )
            return
        }
        VideoNav.Root -> Unit
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 折疊機攤開、平板橫向等寬螢幕時改用側邊導覽列——底部導覽列在寬螢幕上會讓左右兩側
        // 的觸控熱區離拇指太遠,側邊列才是 Material 建議的寬螢幕導覽方式。
        val useNavRail = maxWidth >= AdaptiveNavigationBreakpoint

        if (useNavRail) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.width(RailWidth).fillMaxHeight()) {
                    AppTab.entries.forEach { entry ->
                        NavigationRailItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = {
                                Icon(
                                    entry.icon(),
                                    contentDescription = null,
                                    modifier = Modifier.size(RailIconSize),
                                )
                            },
                            label = { Text(entry.label) },
                        )
                    }
                }
                MainScaffold(
                    modifier = Modifier.weight(1f),
                    tab = tab,
                    service = service,
                    connectionState = connectionState,
                    snackbarHostState = snackbarHostState,
                    bottomBar = null,
                    onWatch = { server -> videoNav = VideoNav.Manifest(server) },
                    onOpenHostControl = { videoNav = VideoNav.HostControl },
                )
            }
        } else {
            MainScaffold(
                modifier = Modifier.fillMaxSize(),
                tab = tab,
                service = service,
                connectionState = connectionState,
                snackbarHostState = snackbarHostState,
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { Icon(entry.icon(), contentDescription = null) },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                },
                onWatch = { server -> videoNav = VideoNav.Manifest(server) },
                onOpenHostControl = { videoNav = VideoNav.HostControl },
            )
        }
    }

    pendingConnectionRequest?.let { request ->
        ConnectionRequestDialog(
            request = request,
            onRespond = { accept, trust -> service.respondToConnectionRequest(accept, trust) },
        )
    }
}

private fun AppTab.icon() = when (this) {
    AppTab.DEVICES -> Icons.Filled.Devices
    AppTab.VIDEO -> Icons.Filled.VideoLibrary
    AppTab.SETTINGS -> Icons.Filled.Settings
}

@Composable
private fun MainScaffold(
    modifier: Modifier,
    tab: AppTab,
    service: ConnectItService,
    connectionState: ConnectionUiState,
    snackbarHostState: SnackbarHostState,
    bottomBar: (@Composable () -> Unit)?,
    onWatch: (DiscoveredDevice) -> Unit,
    onOpenHostControl: () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
        bottomBar = bottomBar ?: {},
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
                onWatch = onWatch,
                onOpenHostControl = onOpenHostControl,
            )
            AppTab.SETTINGS -> SettingsScreen(service = service, modifier = contentModifier)
        }
    }
}
