package com.connectit.android.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.components.BreathingDot
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.theme.BrandColors

/**
 * TV 版「裝置」頁:這台電視 + 附近的裝置;已連線時「目前連線」面板出現在清單上方,
 * 不再整頁換成另一個畫面(對應手機版 DevicesScreen)。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDevicesScreen(service: ConnectItService) {
    val devices by service.devices.collectAsState()
    val connectionState by service.connectionState.collectAsState()
    val settings by service.settingsRepository.settings.collectAsState(
        initial = AppSettings(service.settingsRepository.defaultDeviceName(), AppThemeMode.AUTO)
    )
    val connectedPeer = (connectionState as? ConnectionUiState.Connected)?.peer
    val connectedPeers by service.connectedPeers.collectAsState()
    val connectedAddresses = connectedPeers.map { it.address }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "hero") {
            TvHero(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientAvatar(icon = Icons.Filled.Tv, size = 64.dp)
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("這台裝置", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
                        Text(settings.deviceName, style = MaterialTheme.typography.headlineMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreathingDot(color = Color(0xFF4ADE80), size = 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("廣播中・區網內的裝置都看得到你", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (connectedPeer != null) {
            item(key = "connection") { TvConnectionPanel(service = service, peer = connectedPeer) }
        }

        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("附近的裝置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { service.refreshDevices() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("重新搜尋")
                }
            }
        }

        if (devices.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    TvEmptyState(
                        icon = Icons.Filled.SettingsInputAntenna,
                        title = "正在搜尋附近的裝置…",
                        description = "請確認另一台裝置和這台電視連到同一個網路,而且也開著 ConnectIt。",
                    )
                }
            }
        } else {
            items(devices, key = { it.key }) { device ->
                TvDeviceCard(
                    device = device,
                    isConnected = device.host in connectedAddresses,
                    onConnect = {
                        if (device.host !in connectedAddresses) service.connectTo(device, settings.deviceName)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDeviceCard(device: DiscoveredDevice, isConnected: Boolean, onConnect: () -> Unit) {
    Card(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        border = tvBrandCardBorder(),
        scale = tvBrandCardScale(),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GradientAvatar(icon = Icons.Filled.Devices, size = 52.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayName, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${device.host}:${device.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isConnected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandColors.Success)
                Spacer(Modifier.width(8.dp))
                Text("已連線", style = MaterialTheme.typography.titleMedium, color = BrandColors.Success)
            } else {
                Text("按 OK 連線", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
