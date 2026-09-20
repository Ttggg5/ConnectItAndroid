package com.connectit.android.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.components.ConnectionRequestDialog

private enum class TvTab(val label: String) { DEVICES("裝置"), VIDEO("影片"), SETTINGS("設定") }

/** 對應手機版 AppRoot.kt 裡私有的 VideoNav——TV 版畫面完全不同(見 ui/tv 底下各個 Tv*Screen),
 * 沒辦法直接共用那個 private 型別,所以另外宣告一份一樣結構的。 */
private sealed interface TvVideoNav {
    data object Root : TvVideoNav
    data object HostControl : TvVideoNav
    data class Manifest(val server: DiscoveredDevice) : TvVideoNav
    data class Player(
        val server: DiscoveredDevice,
        val entries: List<VideoManifestEntry>,
        val startIndex: Int,
        val remoteControlHint: Boolean,
    ) : TvVideoNav
}

/**
 * Android TV 版的畫面入口(見 [com.connectit.android.util.isTelevision] 判斷何時使用這個而不是
 * [com.connectit.android.ui.ConnectItApp])。側邊常駐導覽抽屜(NavigationDrawer)取代手機版的
 * 底部導覽列/NavigationRail——底部導覽列在 10 呎外用遙控器操作很不順手,TV app 慣例都是左側常駐
 * 導覽。業務邏輯(裝置搜尋、連線、影片伺服器等)完全沿用同一個 [ConnectItService],這裡只負責
 * 換一套操作方式的畫面。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAppRoot(service: ConnectItService) {
    var tab by remember { mutableStateOf(TvTab.DEVICES) }
    var videoNav by remember { mutableStateOf<TvVideoNav>(TvVideoNav.Root) }

    val connectionState by service.connectionState.collectAsState()
    val pendingConnectionRequest by service.pendingConnectionRequest.collectAsState()

    // TV 上沒有 Snackbar 立足的空間(通常整個畫面被內容佔滿,也沒有觸控可以滑掉提示),事件訊息
    // 已經同時寫進「近期紀錄」(見 TvSettingsScreen),這裡只需要把這條 Flow 消耗掉,不用另外顯示。
    LaunchedEffect(service) {
        service.events.collect { }
    }

    when (val nav = videoNav) {
        TvVideoNav.HostControl -> {
            TvVideoHostControlScreen(service = service, onBack = { videoNav = TvVideoNav.Root })
            return
        }
        is TvVideoNav.Manifest -> {
            TvVideoServerScreen(
                server = nav.server,
                onBack = { videoNav = TvVideoNav.Root },
                onPlay = { entries, startIndex, remoteControlEnabled ->
                    videoNav = TvVideoNav.Player(nav.server, entries, startIndex, remoteControlEnabled)
                },
            )
            return
        }
        is TvVideoNav.Player -> {
            TvVideoPlayerScreen(
                server = nav.server,
                entries = nav.entries,
                startIndex = nav.startIndex,
                remoteControlHint = nav.remoteControlHint,
                onBack = { videoNav = TvVideoNav.Manifest(nav.server) },
                onExitRemoteControl = { videoNav = TvVideoNav.Root },
            )
            return
        }
        TvVideoNav.Root -> Unit
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    NavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Column(
                modifier = Modifier.fillMaxHeight().padding(vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                TvTab.entries.forEach { entry ->
                    NavigationDrawerItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        leadingContent = { Icon(entry.icon(), contentDescription = entry.label) },
                    ) {
                        Text(entry.label)
                    }
                }
            }
        },
    ) {
        when (tab) {
            TvTab.DEVICES -> {
                val connected = connectionState
                if (connected is ConnectionUiState.Connected) {
                    TvConnectedScreen(service = service, peer = connected.peer)
                } else {
                    TvDevicesScreen(service = service)
                }
            }
            TvTab.VIDEO -> TvVideoScreen(
                service = service,
                onWatch = { server -> videoNav = TvVideoNav.Manifest(server) },
                onOpenHostControl = { videoNav = TvVideoNav.HostControl },
            )
            TvTab.SETTINGS -> TvSettingsScreen(service = service)
        }
    }

    pendingConnectionRequest?.let { request ->
        ConnectionRequestDialog(
            request = request,
            onRespond = { accept, trust -> service.respondToConnectionRequest(accept, trust) },
        )
    }
}

private fun TvTab.icon() = when (this) {
    TvTab.DEVICES -> Icons.Filled.Devices
    TvTab.VIDEO -> Icons.Filled.VideoLibrary
    TvTab.SETTINGS -> Icons.Filled.Settings
}
