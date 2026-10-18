package com.connectit.android.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.repo.SettingsValidation
import com.connectit.android.service.ConnectItService
import com.connectit.android.ui.components.AdaptiveContentWidth
import com.connectit.android.ui.components.AutoSaveNumberField
import com.connectit.android.ui.components.AutoSaveTextField
import com.connectit.android.ui.components.ClickRow
import com.connectit.android.ui.components.GhostButton
import com.connectit.android.ui.components.SettingsDivider
import com.connectit.android.ui.components.SettingsGroup
import com.connectit.android.ui.components.SettingsSubheader
import com.connectit.android.ui.components.SwitchRow
import com.connectit.android.ui.components.rememberSavedTicker
import com.connectit.android.video.VideoServerPlaybackOptions
import com.connectit.android.video.VideoSort
import kotlinx.coroutines.launch

/**
 * 設定頁:分成幾張分組卡片,所有變更都即時自動儲存(沒有「儲存」按鈕),
 * 數字欄位停止輸入後自動驗證、不合法就顯示原因;很少用到的連線參數收在「進階」裡。
 */
@Composable
fun SettingsScreen(
    service: ConnectItService,
    modifier: Modifier = Modifier,
    onShowOnboarding: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = service.settingsRepository
    val settings by repo.settings.collectAsState(
        initial = AppSettings(repo.defaultDeviceName(), AppThemeMode.AUTO)
    )
    val logMessages by service.logMessages.collectAsState()

    val deviceSaved = rememberSavedTicker()
    val receiveSaved = rememberSavedTicker()
    val videoSaved = rememberSavedTicker()
    val advancedSaved = rememberSavedTicker()

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch {
                repo.setCustomDownloadFolderUri(uri.toString())
                receiveSaved.bump()
            }
        }
    }

    AdaptiveContentWidth(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
                Text("設定", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "變更會立即自動儲存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ===== 外觀 =====
            SettingsGroup(title = "外觀", icon = Icons.Filled.Palette) {
                val options = listOf(
                    Triple(AppThemeMode.LIGHT, "淺色", Icons.Filled.LightMode),
                    Triple(AppThemeMode.DARK, "深色", Icons.Filled.DarkMode),
                    Triple(AppThemeMode.AUTO, "跟隨系統", Icons.Filled.BrightnessAuto),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    options.forEachIndexed { index, (mode, label, icon) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { scope.launch { repo.setThemeMode(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            icon = {
                                SegmentedButtonDefaults.Icon(active = settings.themeMode == mode) {
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            },
                        ) { Text(label) }
                    }
                }
            }

            // ===== 這台裝置 =====
            SettingsGroup(
                title = "這台裝置",
                icon = Icons.Filled.PhoneAndroid,
                description = "其他裝置會用這個名字找到你。",
                savedTick = deviceSaved.value,
            ) {
                AutoSaveTextField(
                    label = "裝置名稱",
                    initialValue = settings.deviceName,
                    validate = SettingsValidation::validateDeviceName,
                    onSave = { name ->
                        if (name.trim() != settings.deviceName) {
                            scope.launch {
                                repo.setDeviceName(name.trim())
                                deviceSaved.bump()
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // ===== 檔案接收 =====
            SettingsGroup(
                title = "檔案接收",
                icon = Icons.Filled.FolderOpen,
                description = "收到的檔案會儲存到這個資料夾。",
                savedTick = receiveSaved.value,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        service.downloadDisplayPath,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(onClick = { folderPickerLauncher.launch(null) }, text = "變更資料夾")
                    if (settings.customDownloadFolderUri != null) {
                        TextButton(onClick = {
                            scope.launch {
                                repo.setCustomDownloadFolderUri(null)
                                receiveSaved.bump()
                            }
                        }) { Text("還原預設") }
                    }
                }
            }

            // ===== 通知 =====
            SettingsGroup(title = "通知", icon = Icons.Filled.NotificationsNone) {
                SwitchRow(
                    title = "傳輸與斷線通知",
                    description = "建立連線、收到檔案、對方斷線時跳出系統通知。連線請求一律會通知,不受這個設定影響。",
                    checked = settings.notificationsEnabled,
                    onCheckedChange = { scope.launch { repo.setNotificationsEnabled(it) } },
                )
            }

            // ===== 信任的裝置 =====
            SettingsGroup(
                title = "信任的裝置",
                icon = Icons.Filled.VerifiedUser,
                description = "信任的裝置送出連線請求時會自動接受,不再跳出確認視窗。",
            ) {
                if (settings.trustedDeviceNames.isEmpty()) {
                    Text(
                        "目前沒有信任的裝置。收到連線請求時勾選「信任此裝置」即可加入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        settings.trustedDeviceNames.sorted().forEach { name ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(name, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { scope.launch { repo.untrustDevice(name) } }) {
                                        Icon(Icons.Filled.Close, contentDescription = "取消信任")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ===== 影片分享預設值 =====
            SettingsGroup(
                title = "影片分享預設值",
                icon = Icons.Filled.MovieFilter,
                description = "下次開始分享影片時,觀看頁一開始的排序與播放行為。",
                savedTick = videoSaved.value,
            ) {
                var sortMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    ClickRow(
                        title = "排序方式",
                        value = VideoSort.entries.firstOrNull { it.value == settings.videoDefaultSort }?.label,
                        onClick = { sortMenuExpanded = true },
                    )
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                        VideoSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = if (option.value == settings.videoDefaultSort) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    scope.launch {
                                        repo.setVideoDefaultSort(option.value)
                                        videoSaved.bump()
                                    }
                                },
                            )
                        }
                    }
                }
                SettingsDivider()
                SwitchRow(
                    title = "自動播放下一部",
                    checked = settings.videoAutoplayNext,
                    onCheckedChange = { scope.launch { repo.setVideoAutoplayNext(it); videoSaved.bump() } },
                )
                SwitchRow(
                    title = "隨機播放",
                    checked = settings.videoShuffle,
                    onCheckedChange = { scope.launch { repo.setVideoShuffle(it); videoSaved.bump() } },
                )
                SettingsDivider()

                // 拖曳中只更新畫面上的數字,放開才寫入,不用每一格都存一次。
                var volumeDraft by remember { mutableFloatStateOf(settings.videoDefaultVolumePercent.toFloat()) }
                LaunchedEffect(settings.videoDefaultVolumePercent) { volumeDraft = settings.videoDefaultVolumePercent.toFloat() }
                SettingsSubheader("預設音量:${volumeDraft.toInt()}%")
                Slider(
                    value = volumeDraft,
                    onValueChange = { volumeDraft = it },
                    onValueChangeFinished = {
                        scope.launch { repo.setVideoDefaultVolumePercent(volumeDraft.toInt()); videoSaved.bump() }
                    },
                    valueRange = 0f..100f,
                )

                var speedMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    ClickRow(
                        title = "預設播放速度",
                        value = formatSpeedLabel(settings.videoDefaultSpeed),
                        onClick = { speedMenuExpanded = true },
                    )
                    DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                        VideoServerPlaybackOptions.VALID_PLAYBACK_SPEEDS.forEach { speedOption ->
                            DropdownMenuItem(
                                text = { Text(formatSpeedLabel(speedOption)) },
                                leadingIcon = if (speedOption == settings.videoDefaultSpeed) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    speedMenuExpanded = false
                                    scope.launch { repo.setVideoDefaultSpeed(speedOption); videoSaved.bump() }
                                },
                            )
                        }
                    }
                }
            }

            // ===== 進階 =====
            var advancedExpanded by rememberSaveable { mutableStateOf(false) }
            SettingsGroup(
                title = "進階",
                icon = Icons.Filled.Tune,
                description = "一般情況不需要調整。遇到防火牆只開放特定連接埠、或網路比較慢時再來這裡設定。",
                savedTick = advancedSaved.value,
            ) {
                ClickRow(
                    title = if (advancedExpanded) "隱藏進階設定" else "顯示進階設定",
                    onClick = { advancedExpanded = !advancedExpanded },
                    showChevron = false,
                    trailing = {
                        Icon(
                            if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                        )
                    },
                )
                AnimatedVisibility(visible = advancedExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        AutoSaveNumberField(
                            label = "監聽連接埠",
                            initialValue = settings.preferredPort.takeIf { it != 0 }?.toString() ?: "",
                            validate = SettingsValidation::validatePort,
                            onSave = { port ->
                                if (port != settings.preferredPort) {
                                    scope.launch { repo.setPreferredPort(port); advancedSaved.bump() }
                                }
                            },
                            placeholder = "自動指派",
                            supportingText = "留空由系統自動指派。變更後要重新啟動 App 才會生效。",
                        )
                        AutoSaveNumberField(
                            label = "連線逾時秒數",
                            initialValue = settings.connectTimeoutSeconds.toString(),
                            validate = SettingsValidation::validateConnectTimeout,
                            onSave = { seconds ->
                                if (seconds != settings.connectTimeoutSeconds) {
                                    scope.launch { repo.setConnectTimeoutSeconds(seconds); advancedSaved.bump() }
                                }
                            },
                            supportingText = "主動連線時,等待對方網路回應的最長時間(3–120 秒)。",
                        )
                        AutoSaveTextField(
                            label = "額外當作影片的副檔名",
                            initialValue = formatExtraExtensions(settings.videoExtraExtensions),
                            onSave = { text ->
                                scope.launch { repo.setVideoExtraExtensions(text); advancedSaved.bump() }
                            },
                            placeholder = "例如 .ogv, .3gp",
                            supportingText = "以逗號分隔;分享影片資料夾時,這些副檔名的檔案也會列入。",
                        )
                    }
                }
            }

            // ===== 說明與診斷 =====
            var logExpanded by rememberSaveable { mutableStateOf(false) }
            SettingsGroup(title = "說明與診斷", icon = Icons.AutoMirrored.Filled.HelpOutline) {
                ClickRow(
                    title = "重新顯示使用引導",
                    value = "再看一次怎麼配對、傳檔與分享影片。",
                    onClick = onShowOnboarding,
                    trailing = { Icon(Icons.Filled.WavingHand, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    showChevron = false,
                )
                SettingsDivider()
                ClickRow(
                    title = "狀態紀錄",
                    value = "連不上其他裝置時,這裡的紀錄可以幫忙找出原因。",
                    onClick = { logExpanded = !logExpanded },
                    showChevron = false,
                    trailing = { Icon(if (logExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null) },
                )
                AnimatedVisibility(visible = logExpanded) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Column(
                            Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            if (logMessages.isEmpty()) {
                                Text("(還沒有紀錄)", style = MaterialTheme.typography.bodySmall)
                            }
                            logMessages.takeLast(50).asReversed().forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                SettingsDivider()
                ClickRow(
                    title = "停止背景服務並結束",
                    value = "關閉後其他裝置就找不到這台,直到下次開啟 App。",
                    onClick = {
                        context.stopService(Intent(context, ConnectItService::class.java))
                        (context as? Activity)?.finishAndRemoveTask()
                    },
                    showChevron = false,
                    trailing = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}

/** "0.5"/"1"/"1.25" 這種不帶多餘 ".0" 的格式,對應 Windows 設定頁下拉選單顯示的文字。 */
private fun formatSpeedLabel(value: Double): String {
    val trimmed = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    return "${trimmed}x"
}

private fun formatExtraExtensions(extensions: List<String>): String = extensions.joinToString(", ") { ".$it" }
