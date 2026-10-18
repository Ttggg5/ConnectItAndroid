package com.connectit.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.model.VideoManifestEntry
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.RecentlyWatched
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.components.AdaptiveNavigationBreakpoint
import com.connectit.android.ui.components.ConnectFailureDialog
import com.connectit.android.ui.components.ConnectionRequestDialog
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.components.GradientButton
import com.connectit.android.ui.components.RadarPulse
import com.connectit.android.ui.components.WaitingForAcceptDialog
import com.connectit.android.ui.screens.DevicesScreen
import com.connectit.android.ui.screens.OnboardingScreen
import com.connectit.android.ui.screens.SettingsScreen
import com.connectit.android.ui.screens.VideoHostControlScreen
import com.connectit.android.ui.screens.VideoPlayerScreen
import com.connectit.android.ui.screens.VideoScreen
import com.connectit.android.ui.screens.VideoServerScreen
import com.connectit.android.ui.theme.BrandColors
import com.connectit.android.util.SafUtils
import kotlinx.coroutines.launch

private enum class AppTab(val label: String) { DEVICES("裝置"), VIDEO("影片"), SETTINGS("設定") }

/** Material 預設的 NavigationRail 只有 80dp 寬,圖示和文字標籤會擠在一起;加寬一點比較好按、好讀。 */
private val RailWidth = 104.dp

/** 「影片」頁的子導覽:選伺服器 -> 看清單(向對方要 manifest)-> 播放。 */
private sealed interface VideoNav {
    data object Root : VideoNav
    data object HostControl : VideoNav

    /** [resume] 從「繼續觀看」進來時要直接播放的那一部(清單載入後自動跳進播放畫面)。 */
    data class Manifest(val server: DiscoveredDevice, val resume: RecentlyWatched? = null) : VideoNav
    data class Player(
        val server: DiscoveredDevice,
        val entries: List<VideoManifestEntry>,
        val startIndex: Int,
        val remoteControlHint: Boolean,
        val startPositionMs: Long? = null,
    ) : VideoNav
}

@Composable
fun ConnectItApp(service: ConnectItService, viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(AppTab.DEVICES) }
    var videoNav by remember { mutableStateOf<VideoNav>(VideoNav.Root) }
    var showOnboardingAgain by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val connectionState by service.connectionState.collectAsState()
    val pendingConnectionRequest by service.pendingConnectionRequest.collectAsState()
    val connectingTo by service.connectingTo.collectAsState()
    val connectFailure by service.connectFailure.collectAsState()
    val pendingShare by viewModel.pendingShare.collectAsState()
    // 初始值 null 代表 DataStore 還沒讀出來:這段期間不要決定要不要顯示引導,避免閃一下。
    val settings by service.settingsRepository.settings.collectAsState(initial = null)

    LaunchedEffect(service) {
        service.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // 回到前景時重新查詢一次附近的裝置/影片伺服器,讓剛上線的裝置馬上出現(對應 Windows 端視窗啟用時的快速搜尋)。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, service) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) service.refreshDevices()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 已經看過引導的使用者(例如從舊版升級)維持原本「開 App 就詢問通知權限」的行為;
    // 第一次使用的人則在引導最後一步才詢問,先讓使用者知道為什麼需要。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(settings?.onboardingCompleted) {
        if (settings?.onboardingCompleted == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !viewModel.notificationPermissionRequested) {
            viewModel.notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val currentSettings = settings
    if (currentSettings != null && (!currentSettings.onboardingCompleted || showOnboardingAgain)) {
        OnboardingScreen(initialDeviceName = currentSettings.deviceName) { name ->
            scope.launch {
                if (name != null && name.isNotBlank()) service.settingsRepository.setDeviceName(name)
                service.settingsRepository.setOnboardingCompleted(true)
            }
            showOnboardingAgain = false
            viewModel.notificationPermissionRequested = true
            tab = AppTab.DEVICES
        }
        return
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
                    val resume = nav.resume?.takeIf { entries.getOrNull(startIndex)?.relativePath == it.relativePath }
                    videoNav = VideoNav.Player(nav.server, entries, startIndex, remoteControlEnabled, resume?.positionMs)
                },
                resumePath = nav.resume?.relativePath,
            )
            return
        }
        is VideoNav.Player -> {
            VideoPlayerScreen(
                server = nav.server,
                entries = nav.entries,
                startIndex = nav.startIndex,
                remoteControlHint = nav.remoteControlHint,
                startPositionMs = nav.startPositionMs,
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
        val isConnected = connectionState is ConnectionUiState.Connected

        val screen: @Composable (Modifier) -> Unit = { contentModifier ->
            when (tab) {
                AppTab.DEVICES -> DevicesScreen(service = service, modifier = contentModifier)
                AppTab.VIDEO -> VideoScreen(
                    service = service,
                    modifier = contentModifier,
                    onWatch = { server -> videoNav = VideoNav.Manifest(server) },
                    onOpenHostControl = { videoNav = VideoNav.HostControl },
                    onResume = { server, entry -> videoNav = VideoNav.Manifest(server, entry) },
                )
                AppTab.SETTINGS -> SettingsScreen(
                    service = service,
                    modifier = contentModifier,
                    onShowOnboarding = { showOnboardingAgain = true },
                )
            }
        }

        if (useNavRail) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.width(RailWidth).fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    header = {
                        GradientAvatar(
                            icon = Icons.Filled.Hub,
                            size = 48.dp,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                        )
                    },
                ) {
                    AppTab.entries.forEach { entry ->
                        NavigationRailItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { TabIcon(entry, showConnectedDot = entry == AppTab.DEVICES && isConnected) },
                            label = { Text(entry.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                MainScaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHostState = snackbarHostState,
                    bottomBar = null,
                    content = screen,
                )
            }
        } else {
            MainScaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHostState = snackbarHostState,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        AppTab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { TabIcon(entry, showConnectedDot = entry == AppTab.DEVICES && isConnected) },
                                label = { Text(entry.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                },
                content = screen,
            )
        }
    }

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

    if (pendingShare.isNotEmpty() && currentSettings != null) {
        ShareTargetSheet(
            service = service,
            uris = pendingShare,
            settings = currentSettings,
            onDone = { viewModel.clearPendingShare() },
            onSent = { tab = AppTab.DEVICES },
        )
    }
}

@Composable
private fun TabIcon(tab: AppTab, showConnectedDot: Boolean) {
    Box {
        Icon(tab.icon(), contentDescription = null)
        if (showConnectedDot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(BrandColors.Success),
            )
        }
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
    snackbarHostState: SnackbarHostState,
    bottomBar: (@Composable () -> Unit)?,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                )
            }
        },
        bottomBar = bottomBar ?: {},
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

/**
 * 從其他 App「分享」檔案到 ConnectIt 時跳出的「傳送給…」底部面板:已連線就直接傳給目前的對象;
 * 還沒連線就列出附近的裝置,選一台送出連線請求,對方接受後自動開始傳送。
 */
@Composable
private fun ShareTargetSheet(
    service: ConnectItService,
    uris: List<Uri>,
    settings: AppSettings,
    onDone: () -> Unit,
    onSent: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val devices by service.devices.collectAsState()
    val connectionState by service.connectionState.collectAsState()
    val peer = (connectionState as? ConnectionUiState.Connected)?.peer
    val files = remember(uris) {
        uris.map { uri ->
            val (name, size) = SafUtils.queryNameAndSize(context.contentResolver, uri)
            Triple(uri, name, size)
        }
    }

    fun send() {
        service.sendFiles(files)
        onSent()
        onDone()
    }

    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                if (files.size == 1) "傳送檔案" else "傳送 ${files.size} 個檔案",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(10.dp))
            files.take(3).forEach { (_, name, _) ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (files.size > 3) {
                Text("…還有 ${files.size - 3} 個", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(18.dp))

            if (peer != null) {
                GradientButton(
                    onClick = ::send,
                    text = "傳送給 ${peer.name}",
                    icon = Icons.AutoMirrored.Filled.Send,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp),
                )
            } else {
                Text("選擇要傳送給哪台裝置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        RadarPulse(icon = Icons.Filled.SettingsInputAntenna, size = 120.dp)
                        Text(
                            "正在搜尋附近的裝置…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        devices.forEach { device ->
                            Surface(
                                onClick = {
                                    // 對方接受後才傳送;Service 會顯示「等待對方接受」對話框與失敗原因。
                                    service.connectTo(device, settings.deviceName, onAccepted = { service.sendFiles(files) })
                                    onSent()
                                    onDone()
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    GradientAvatar(icon = Icons.Filled.Devices, size = 40.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${device.host}:${device.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "傳送", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
