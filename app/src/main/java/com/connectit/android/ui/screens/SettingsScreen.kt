package com.connectit.android.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.service.ConnectItService
import com.connectit.android.ui.components.AdaptiveContentWidth
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(service: ConnectItService, modifier: Modifier = Modifier) {
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

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch { service.settingsRepository.setCustomDownloadFolderUri(uri.toString()) }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("設定") }) },
    ) { padding ->
        AdaptiveContentWidth(modifier = Modifier.padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = deviceNameText,
                onValueChange = { deviceNameText = it },
                label = { Text("裝置名稱") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(onClick = { scope.launch { service.settingsRepository.setDeviceName(deviceNameText) } }) {
                Text("儲存裝置名稱")
            }

            HorizontalDivider()

            Text("外觀主題", style = MaterialTheme.typography.titleMedium)
            ThemeOption("跟隨系統", AppThemeMode.AUTO, settings.themeMode) {
                scope.launch { service.settingsRepository.setThemeMode(it) }
            }
            ThemeOption("淺色", AppThemeMode.LIGHT, settings.themeMode) {
                scope.launch { service.settingsRepository.setThemeMode(it) }
            }
            ThemeOption("深色", AppThemeMode.DARK, settings.themeMode) {
                scope.launch { service.settingsRepository.setThemeMode(it) }
            }

            HorizontalDivider()

            Text("通知", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("傳輸與斷線通知")
                    Text(
                        "建立連線、收到檔案/資料夾、對方斷線時跳出系統通知(連線請求一律會通知,不受此設定影響)。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.notificationsEnabled,
                    onCheckedChange = { scope.launch { service.settingsRepository.setNotificationsEnabled(it) } },
                )
            }

            HorizontalDivider()

            Text("信任的裝置", style = MaterialTheme.typography.titleMedium)
            Text(
                "信任的裝置送出連線請求時會直接自動接受,不再跳出確認對話框。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.trustedDeviceNames.isEmpty()) {
                Text(
                    "目前沒有信任的裝置(可在收到連線請求時勾選「信任此裝置」加入)。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column {
                    settings.trustedDeviceNames.sorted().forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(name)
                            IconButton(onClick = { scope.launch { service.settingsRepository.untrustDevice(name) } }) {
                                Icon(Icons.Filled.Close, contentDescription = "移除信任")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Text("接收檔案儲存位置", style = MaterialTheme.typography.titleMedium)
            Text(service.downloadDisplayPath, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPickerLauncher.launch(null) }) {
                    Text("選擇資料夾")
                }
                if (settings.customDownloadFolderUri != null) {
                    TextButton(onClick = { scope.launch { service.settingsRepository.setCustomDownloadFolderUri(null) } }) {
                        Text("還原預設")
                    }
                }
            }

            HorizontalDivider()

            Text("連線設定", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = portText,
                onValueChange = { text -> if (text.all { it.isDigit() } && text.length <= 5) portText = text },
                label = { Text("監聽連接埠(留空 = 自動)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { text -> if (text.all { it.isDigit() } && text.length <= 3) timeoutText = text },
                label = { Text("連線逾時秒數") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    service.settingsRepository.setPreferredPort(portText.toIntOrNull() ?: 0)
                    service.settingsRepository.setConnectTimeoutSeconds(
                        timeoutText.toIntOrNull() ?: AppSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS
                    )
                }
            }) {
                Text("儲存連線設定")
            }
            Text(
                "連接埠變更需要重新啟動 App 才會生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("近期紀錄", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(logMessages.takeLast(50).asReversed()) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider()

            OutlinedButton(onClick = {
                context.stopService(Intent(context, com.connectit.android.service.ConnectItService::class.java))
                (context as? Activity)?.finishAndRemoveTask()
            }) {
                Text("停止背景服務並結束")
            }
        }
        }
    }
}

@Composable
private fun ThemeOption(label: String, value: AppThemeMode, selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(label)
    }
}
