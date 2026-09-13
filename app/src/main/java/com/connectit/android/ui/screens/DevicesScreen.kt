package com.connectit.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.service.ConnectItService
import com.connectit.android.ui.components.AdaptiveContentWidth

@Composable
fun DevicesScreen(service: ConnectItService, modifier: Modifier = Modifier) {
    val devices by service.devices.collectAsState()
    val settings by service.settingsRepository.settings.collectAsState(
        initial = AppSettings(service.settingsRepository.defaultDeviceName(), AppThemeMode.AUTO)
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("裝置") },
                actions = {
                    IconButton(onClick = { service.refreshDevices() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新搜尋")
                    }
                },
            )
        },
    ) { padding ->
        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("正在搜尋裝置...", modifier = Modifier.padding(top = 16.dp))
                }
            }
            return@Scaffold
        }

        AdaptiveContentWidth(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(devices, key = { it.key }) { device ->
                    DeviceCard(device = device, onConnect = { service.connectTo(device, settings.deviceName) })
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DiscoveredDevice, onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onConnect) {
        ListItem(
            headlineContent = { Text(device.displayName) },
            supportingContent = { Text("${device.host}:${device.port}") },
            leadingContent = { Icon(Icons.Filled.PhoneAndroid, contentDescription = null) },
        )
    }
}
