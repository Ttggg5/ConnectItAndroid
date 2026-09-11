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
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.components.ConnectionRequestDialog
import com.connectit.android.ui.components.FileOfferDialog
import com.connectit.android.ui.components.FolderOfferDialog
import com.connectit.android.ui.screens.ConnectedScreen
import com.connectit.android.ui.screens.DevicesScreen
import com.connectit.android.ui.screens.SettingsScreen
import com.connectit.android.ui.screens.VideoScreen
import com.connectit.android.ui.screens.VideoWebViewScreen

private enum class AppTab(val label: String) { DEVICES("裝置"), VIDEO("影片"), SETTINGS("設定") }

@Composable
fun ConnectItApp(service: ConnectItService) {
    var tab by remember { mutableStateOf(AppTab.DEVICES) }
    var watchingServer by remember { mutableStateOf<DiscoveredDevice?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val connectionState by service.connectionState.collectAsState()
    val pendingConnectionRequest by service.pendingConnectionRequest.collectAsState()
    val pendingFileOffer by service.pendingFileOffer.collectAsState()
    val pendingFolderOffer by service.pendingFolderOffer.collectAsState()

    LaunchedEffect(service) {
        service.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    val watching = watchingServer
    if (watching != null) {
        VideoWebViewScreen(server = watching, onBack = { watchingServer = null })
        return
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
            AppTab.VIDEO -> VideoScreen(service = service, modifier = contentModifier, onWatch = { watchingServer = it })
            AppTab.SETTINGS -> SettingsScreen(service = service, modifier = contentModifier)
        }
    }

    pendingConnectionRequest?.let { request ->
        ConnectionRequestDialog(
            request = request,
            onRespond = { accept -> service.respondToConnectionRequest(accept) },
        )
    }

    pendingFileOffer?.let { offer ->
        FileOfferDialog(
            offer = offer,
            onRespond = { accept -> service.respondToFileOffer(offer.transferId, accept) },
        )
    }

    pendingFolderOffer?.let { offer ->
        FolderOfferDialog(
            offer = offer,
            onRespond = { accept -> service.respondToFolderOffer(offer.transferId, accept) },
        )
    }
}
