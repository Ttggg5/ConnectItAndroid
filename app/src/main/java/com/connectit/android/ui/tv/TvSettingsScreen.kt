package com.connectit.android.ui.tv

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.service.ConnectItService
import com.connectit.android.video.VideoServerPlaybackOptions
import com.connectit.android.video.VideoSort
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(service: ConnectItService) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by service.settingsRepository.settings.collectAsState(
        initial = AppSettings(service.settingsRepository.defaultDeviceName(), AppThemeMode.AUTO)
    )
    val logMessages by service.logMessages.collectAsState()

    var deviceNameText by remember { mutableStateOf(settings.deviceName) }
    LaunchedEffect(settings.deviceName) { deviceNameText = settings.deviceName }

    var portText by remember { mutableStateOf(settings.preferredPort.takeIf { it != 0 }?.toString() ?: "") }
    LaunchedEffect(settings.preferredPort) { portText = settings.preferredPort.takeIf { it != 0 }?.toString() ?: "" }

    var timeoutText by remember { mutableStateOf(settings.connectTimeoutSeconds.toString()) }
    LaunchedEffect(settings.connectTimeoutSeconds) { timeoutText = settings.connectTimeoutSeconds.toString() }

    var autoplayCountdownText by remember { mutableStateOf(settings.videoAutoplayCountdownSeconds.toString()) }
    LaunchedEffect(settings.videoAutoplayCountdownSeconds) { autoplayCountdownText = settings.videoAutoplayCountdownSeconds.toString() }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch { service.settingsRepository.setCustomDownloadFolderUri(uri.toString()) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)

        SettingsSection(title = "裝置名稱") {
            TvOutlinedTextField(value = deviceNameText, onValueChange = { deviceNameText = it }, label = "裝置名稱")
            OutlinedButton(onClick = { scope.launch { service.settingsRepository.setDeviceName(deviceNameText) } }) {
                Text("儲存裝置名稱")
            }
        }

        SettingsSection(title = "外觀主題") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeOption("跟隨系統", AppThemeMode.AUTO, settings.themeMode) {
                    scope.launch { service.settingsRepository.setThemeMode(it) }
                }
                ThemeOption("淺色", AppThemeMode.LIGHT, settings.themeMode) {
                    scope.launch { service.settingsRepository.setThemeMode(it) }
                }
                ThemeOption("深色", AppThemeMode.DARK, settings.themeMode) {
                    scope.launch { service.settingsRepository.setThemeMode(it) }
                }
            }
        }

        SettingsSection(title = "通知") {
            SwitchRow(
                title = "傳輸與斷線通知",
                subtitle = "建立連線、收到檔案/資料夾、對方斷線時跳出系統通知(連線請求一律會通知,不受此設定影響)。",
                checked = settings.notificationsEnabled,
                onCheckedChange = { scope.launch { service.settingsRepository.setNotificationsEnabled(it) } },
            )
        }

        SettingsSection(title = "信任的裝置") {
            Text(
                "信任的裝置送出連線請求時會直接自動接受,不再跳出確認對話框。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (settings.trustedDeviceNames.isEmpty()) {
                Text("目前沒有信任的裝置(可在收到連線請求時勾選「信任此裝置」加入)。", style = MaterialTheme.typography.bodySmall)
            } else {
                settings.trustedDeviceNames.sorted().forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name)
                        IconButton(onClick = { scope.launch { service.settingsRepository.untrustDevice(name) } }) {
                            Icon(Icons.Filled.Close, contentDescription = "移除信任")
                        }
                    }
                }
            }
        }

        SettingsSection(title = "接收檔案儲存位置") {
            Text(service.downloadDisplayPath, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { folderPickerLauncher.launch(null) }) { Text("選擇資料夾") }
                if (settings.customDownloadFolderUri != null) {
                    OutlinedButton(onClick = { scope.launch { service.settingsRepository.setCustomDownloadFolderUri(null) } }) {
                        Text("還原預設")
                    }
                }
            }
        }

        SettingsSection(title = "連線設定") {
            TvOutlinedTextField(
                value = portText,
                onValueChange = { text -> if (text.all { it.isDigit() } && text.length <= 5) portText = text },
                label = "監聽連接埠(留空 = 自動)",
            )
            TvOutlinedTextField(
                value = timeoutText,
                onValueChange = { text -> if (text.all { it.isDigit() } && text.length <= 3) timeoutText = text },
                label = "連線逾時秒數",
            )
            OutlinedButton(onClick = {
                scope.launch {
                    service.settingsRepository.setPreferredPort(portText.toIntOrNull() ?: 0)
                    service.settingsRepository.setConnectTimeoutSeconds(
                        timeoutText.toIntOrNull() ?: AppSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS
                    )
                }
            }) { Text("儲存連線設定") }
            Text("連接埠變更需要重新啟動 App 才會生效。", style = MaterialTheme.typography.bodySmall)
        }

        SettingsSection(title = "影片伺服器") {
            Text(
                "下次開始分享影片時套用的預設值:清單排序方式、播放器初始行為,以及自動播放下一部的倒數秒數。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("預設排序方式", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoSort.entries.forEach { option ->
                    ChoiceChip(
                        label = option.label,
                        selected = option.value == settings.videoDefaultSort,
                        onClick = { scope.launch { service.settingsRepository.setVideoDefaultSort(option.value) } },
                    )
                }
            }

            SwitchRow(
                title = "預設自動播放下一部",
                subtitle = null,
                checked = settings.videoAutoplayNext,
                onCheckedChange = { scope.launch { service.settingsRepository.setVideoAutoplayNext(it) } },
            )
            SwitchRow(
                title = "預設隨機播放",
                subtitle = null,
                checked = settings.videoShuffle,
                onCheckedChange = { scope.launch { service.settingsRepository.setVideoShuffle(it) } },
            )

            TvOutlinedTextField(
                value = autoplayCountdownText,
                onValueChange = { text -> if (text.all { it.isDigit() } && text.length <= 2) autoplayCountdownText = text },
                label = "自動播放倒數秒數(1-30)",
            )
            OutlinedButton(onClick = {
                scope.launch {
                    service.settingsRepository.setVideoAutoplayCountdownSeconds(
                        autoplayCountdownText.toIntOrNull() ?: VideoServerPlaybackOptions.DEFAULT_AUTOPLAY_COUNTDOWN_SECONDS,
                    )
                }
            }) { Text("儲存倒數秒數") }

            Text("預設音量:${settings.videoDefaultVolumePercent}%", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { service.settingsRepository.setVideoDefaultVolumePercent((settings.videoDefaultVolumePercent - 10).coerceAtLeast(0)) }
                }) { Text("−") }
                OutlinedButton(onClick = {
                    scope.launch { service.settingsRepository.setVideoDefaultVolumePercent((settings.videoDefaultVolumePercent + 10).coerceAtMost(100)) }
                }) { Text("＋") }
            }

            Text("預設播放速度", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoServerPlaybackOptions.VALID_PLAYBACK_SPEEDS.forEach { speedOption ->
                    ChoiceChip(
                        label = formatSpeedLabel(speedOption),
                        selected = speedOption == settings.videoDefaultSpeed,
                        onClick = { scope.launch { service.settingsRepository.setVideoDefaultSpeed(speedOption) } },
                    )
                }
            }
        }

        SettingsSection(title = "近期紀錄") {
            Surface(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                LazyColumn(modifier = Modifier.padding(12.dp)) {
                    items(logMessages.takeLast(50).asReversed()) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        OutlinedButton(onClick = {
            context.stopService(Intent(context, com.connectit.android.service.ConnectItService::class.java))
            (context as? Activity)?.finishAndRemoveTask()
        }) { Text("停止背景服務並結束") }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ThemeOption(label: String, value: AppThemeMode, selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    if (selected == value) {
        Button(onClick = { onSelect(value) }) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSelect(value) }) { Text(label) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** OutlinedTextField 沒有 TV 版(遙控器輸入文字本來就少見,直接沿用 androidx.compose.material3
 * 的實作),但套用 TV 深色主題的色彩,確保跟周圍的 tv-material3 元件對比一致,不會出現看不清楚
 * 的黑底黑字。 */
@Composable
private fun TvOutlinedTextField(value: String, onValueChange: (String) -> Unit, label: String) {
    val tvColorScheme = MaterialTheme.colorScheme
    androidx.compose.material3.MaterialTheme(
        colorScheme = darkColorScheme(primary = tvColorScheme.primary),
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { androidx.compose.material3.Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

private fun formatSpeedLabel(value: Double): String {
    val trimmed = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    return "${trimmed}x"
}
