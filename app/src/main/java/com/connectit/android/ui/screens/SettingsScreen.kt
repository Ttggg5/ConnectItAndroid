package com.connectit.android.ui.screens

import android.app.Activity
import android.content.Intent
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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("設定") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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

            Text("接收檔案儲存位置", style = MaterialTheme.typography.titleMedium)
            Text(service.downloadDisplayPath, style = MaterialTheme.typography.bodySmall)

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
                context.stopService(Intent(context, ConnectItService::class.java))
                (context as? Activity)?.finishAndRemoveTask()
            }) {
                Text("停止背景服務並結束")
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
