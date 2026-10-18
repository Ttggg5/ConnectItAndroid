package com.connectit.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.connectit.android.model.DiscoveredDevice
import com.connectit.android.repo.AppSettings
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.repo.SettingsValidation
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.components.AdaptiveContentWidth
import com.connectit.android.ui.components.EmptyState
import com.connectit.android.ui.components.GlassPill
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.components.GradientButton
import com.connectit.android.ui.components.HeroCard
import com.connectit.android.ui.components.RadarPulse
import com.connectit.android.ui.components.SectionHeader
import com.connectit.android.ui.components.StatusPill
import com.connectit.android.ui.theme.BrandColors
import kotlinx.coroutines.launch

/** 寬到可以把「目前連線」卡片放在裝置清單右側的寬度(平板橫向、折疊機攤開)。 */
private val TwoPaneBreakpoint = 760.dp

/**
 * 「裝置」頁:這台裝置(可就地改名)+ 附近的裝置清單;已連線時「目前連線」卡片出現在清單上方
 * (寬螢幕在右側),不再整頁切換、看不到其他裝置。
 */
@Composable
fun DevicesScreen(service: ConnectItService, modifier: Modifier = Modifier) {
    val devices by service.devices.collectAsState()
    val connectionState by service.connectionState.collectAsState()
    val settings by service.settingsRepository.settings.collectAsState(
        initial = AppSettings(service.settingsRepository.defaultDeviceName(), AppThemeMode.AUTO)
    )
    val connectedPeer = (connectionState as? ConnectionUiState.Connected)?.peer

    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPane = connectedPeer != null && maxWidth >= TwoPaneBreakpoint

        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    deviceListContent(service, settings, devices, connectedPeer?.address)
                }
                LazyColumn(
                    modifier = Modifier.width(380.dp).fillMaxSize(),
                    contentPadding = PaddingValues(top = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    item { ConnectionPanel(service = service, peer = connectedPeer!!) }
                }
            }
        } else {
            AdaptiveContentWidth {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (connectedPeer != null) {
                        item(key = "hero") { DeviceHero(service, settings.deviceName) }
                        item(key = "connection") { ConnectionPanel(service = service, peer = connectedPeer) }
                        deviceListContent(service, settings, devices, connectedPeer.address, includeHero = false)
                    } else {
                        deviceListContent(service, settings, devices, null)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.deviceListContent(
    service: ConnectItService,
    settings: AppSettings,
    devices: List<DiscoveredDevice>,
    connectedAddress: String?,
    includeHero: Boolean = true,
) {
    if (includeHero) {
        item(key = "hero") { DeviceHero(service, settings.deviceName) }
    }
    item(key = "header") {
        SectionHeader(
            title = "附近的裝置",
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            action = {
                TextButton(onClick = { service.refreshDevices() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新搜尋")
                }
            },
        )
    }

    if (devices.isEmpty()) {
        item(key = "empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "正在搜尋附近的裝置…",
                    description = "請確認另一台裝置和這台連到同一個 Wi-Fi,而且也開著 ConnectIt。找到後會自動出現在這裡。",
                ) {
                    RadarPulse(icon = Icons.Filled.SettingsInputAntenna)
                }
            }
        }
    } else {
        items(devices, key = { it.key }) { device ->
            DeviceCard(
                device = device,
                isConnected = connectedAddress != null && device.host == connectedAddress,
                connectEnabled = connectedAddress == null,
                onConnect = { service.connectTo(device, settings.deviceName) },
            )
        }
    }
}

/** 「這台裝置」Hero:裝置名稱(點筆形圖示就地修改)+ 廣播中標籤。 */
@Composable
private fun DeviceHero(service: ConnectItService, deviceName: String) {
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(deviceName) { mutableStateOf(deviceName) }
    val error = if (editing) SettingsValidation.validateDeviceName(draft) else null

    fun commit() {
        if (SettingsValidation.validateDeviceName(draft) != null) return
        scope.launch { service.settingsRepository.setDeviceName(draft.trim()) }
        editing = false
    }

    HeroCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("這台裝置", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                if (editing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(SettingsValidation.MAX_DEVICE_NAME_LENGTH) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { { Text(it, color = Color.White) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                } else {
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val glassButtonColors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
            )
            if (editing) {
                Column {
                    IconButton(onClick = { commit() }, colors = glassButtonColors) {
                        Icon(Icons.Filled.Check, contentDescription = "儲存名稱")
                    }
                    IconButton(onClick = { draft = deviceName; editing = false }, colors = glassButtonColors) {
                        Icon(Icons.Filled.Close, contentDescription = "取消")
                    }
                }
            } else {
                IconButton(onClick = { draft = deviceName; editing = true }, colors = glassButtonColors) {
                    Icon(Icons.Filled.Edit, contentDescription = "修改裝置名稱")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassPill(text = "廣播中・區網內的裝置都看得到你", dotColor = Color(0xFF4ADE80))
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    isConnected: Boolean,
    connectEnabled: Boolean,
    onConnect: () -> Unit,
) {
    Surface(
        onClick = onConnect,
        enabled = connectEnabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (isConnected) BorderStroke(2.dp, BrandColors.Gradient) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GradientAvatar(icon = Icons.Filled.Devices)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${device.host}:${device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                isConnected -> StatusPill(
                    text = "已連線",
                    icon = Icons.Filled.CheckCircle,
                    containerColor = BrandColors.Success.copy(alpha = 0.15f),
                    contentColor = BrandColors.Success,
                )
                else -> GradientButton(
                    onClick = onConnect,
                    enabled = connectEnabled,
                    text = "連線",
                    icon = Icons.Filled.Link,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}
