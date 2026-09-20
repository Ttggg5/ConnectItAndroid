package com.connectit.android.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.service.ConnectItService

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDevicesScreen(service: ConnectItService) {
    val devices by service.devices.collectAsState()
    val settings by service.settingsRepository.settings.collectAsState(
        initial = AppSettings(service.settingsRepository.defaultDeviceName(), AppThemeMode.AUTO)
    )

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("裝置", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { service.refreshDevices() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新搜尋")
            }
        }

        if (devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("正在搜尋裝置...", modifier = Modifier.padding(top = 16.dp))
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(devices, key = { it.key }) { device ->
                TvDeviceCard(device = device, onConnect = { service.connectTo(device, settings.deviceName) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDeviceCard(device: DiscoveredDevice, onConnect: () -> Unit) {
    Card(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null)
            Column {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${device.host}:${device.port}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
