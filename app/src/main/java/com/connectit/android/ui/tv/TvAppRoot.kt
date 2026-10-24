package com.connectit.android.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.repo.RecentlyWatched
import com.connectit.android.ui.components.ConnectFailureDialog
import com.connectit.android.ui.components.ConnectionRequestDialog
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.components.ScreenMirrorHost
import com.connectit.android.ui.screens.ScreenViewerScreen
import com.connectit.android.screen.ScreenMirrorState
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.components.WaitingForAcceptDialog
import com.connectit.android.ui.screens.OnboardingScreen
import com.connectit.android.ui.theme.ConnectItTheme
import kotlinx.coroutines.launch

private enum class TvTab(val label: String) { DEVICES("裝置"), VIDEO("影片"), SETTINGS("設定") }

/** 對應手機版 AppRoot.kt 裡私有的 VideoNav——TV 版畫面完全不同(見 ui/tv 底下各個 Tv*Screen),
 * 沒辦法直接共用那個 private 型別,所以另外宣告一份一樣結構的。 */
private sealed interface TvVideoNav {
    data object Root : TvVideoNav
    data object HostControl : TvVideoNav
    data class Manifest(val server: DiscoveredDevice, val resume: RecentlyWatched? = null) : TvVideoNav
    data class Player(
        val server: DiscoveredDevice,
        val entries: List<VideoManifestEntry>,
        val startIndex: Int,
        val remoteControlHint: Boolean,
        val startPositionMs: Long? = null,
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

    val pendingConnectionRequest by service.pendingConnectionRequest.collectAsState()
    val connectingTo by service.connectingTo.collectAsState()
    val connectFailure by service.connectFailure.collectAsState()
    val screenMirrorState by service.screenMirror.state.collectAsState()
    val connectionState by service.connectionState.collectAsState()
    val settings by service.settingsRepository.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    // TV 上沒有 Snackbar 立足的空間(通常整個畫面被內容佔滿,也沒有觸控可以滑掉提示),事件訊息
    // 已經同時寫進「近期紀錄」(見 TvSettingsScreen),這裡只需要把這條 Flow 消耗掉,不用另外顯示。
    LaunchedEffect(service) {
        service.events.collect { }
    }

    // 對話框與首次啟動引導沿用手機版的元件(androidx.compose.material3),套上同一組品牌主題,
    // 不然會是 Material 預設的紫色系。
    val currentSettings = settings
    if (currentSettings != null && !currentSettings.onboardingCompleted) {
        ConnectItTheme(themeMode = AppThemeMode.DARK) {
            OnboardingScreen(initialDeviceName = currentSettings.deviceName) { name ->
                scope.launch {
                    if (!name.isNullOrBlank()) service.settingsRepository.setDeviceName(name)
                    service.settingsRepository.setOnboardingCompleted(true)
                }
            }
        }
        return
    }

    // 螢幕鏡像的對話框/系統擷取確認沿用手機版元件,放在各個整頁子畫面的提早 return 之前,
    // 不論在哪一頁都會跳出來。
    ConnectItTheme(themeMode = AppThemeMode.DARK) {
        ScreenMirrorHost(service = service)
    }

    // 觀看對方螢幕:TV 沒有觸控,只提供觀看(按返回鍵結束)。
    (screenMirrorState as? ScreenMirrorState.Viewing)?.let { viewing ->
        ConnectItTheme(themeMode = AppThemeMode.DARK) {
            ScreenViewerScreen(
                service = service,
                state = viewing,
                peerName = service.screenMirror.peer?.name ?: "對方",
                allowInput = false,
            )
        }
        return
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
                    val resume = nav.resume?.takeIf { entries.getOrNull(startIndex)?.relativePath == it.relativePath }
                    videoNav = TvVideoNav.Player(nav.server, entries, startIndex, remoteControlEnabled, resume?.positionMs)
                },
                resumePath = nav.resume?.relativePath,
            )
            return
        }
        is TvVideoNav.Player -> {
            TvVideoPlayerScreen(
                server = nav.server,
                entries = nav.entries,
                startIndex = nav.startIndex,
                remoteControlHint = nav.remoteControlHint,
                startPositionMs = nav.startPositionMs,
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
                GradientAvatar(
                    icon = Icons.Filled.Hub,
                    size = 48.dp,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(start = 12.dp, bottom = 24.dp),
                )
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
            TvTab.DEVICES -> TvDevicesScreen(service = service)
            TvTab.VIDEO -> TvVideoScreen(
                service = service,
                onWatch = { server -> videoNav = TvVideoNav.Manifest(server) },
                onOpenHostControl = { videoNav = TvVideoNav.HostControl },
                onResume = { server, entry -> videoNav = TvVideoNav.Manifest(server, entry) },
            )
            TvTab.SETTINGS -> TvSettingsScreen(service = service)
        }
    }

    ConnectItTheme(themeMode = AppThemeMode.DARK) {
        pendingConnectionRequest?.let { request ->
            ConnectionRequestDialog(
                request = request,
                onRespond = { accept, trust -> service.respondToConnectionRequest(accept, trust) },
            )
        }
        connectingTo?.let { device ->
            WaitingForAcceptDialog(deviceName = device.displayName, onCancel = { service.cancelConnect() })
        }
        connectFailure?.let { failure ->
            ConnectFailureDialog(failure = failure, onDismiss = { service.dismissConnectFailure() })
        }
    }
}

private fun TvTab.icon() = when (this) {
    TvTab.DEVICES -> Icons.Filled.Devices
    TvTab.VIDEO -> Icons.Filled.VideoLibrary
    TvTab.SETTINGS -> Icons.Filled.Settings
}
