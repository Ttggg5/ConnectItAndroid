package com.connectit.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.connectit.android.model.ConnectionRequest
import com.connectit.android.model.ConnectionRequestOutcome
import com.connectit.android.service.ConnectFailure

/** 品牌風格的對話框外框:大圓角、置中內容。 */
@Composable
private fun BrandDialog(
    onDismissRequest: () -> Unit,
    dismissOnOutside: Boolean = true,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = dismissOnOutside, dismissOnBackPress = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
        }
    }
}

/** [onRespond] 的第二個參數:是否同時把這台裝置加入信任清單(僅在接受時有意義)。 */
@Composable
fun ConnectionRequestDialog(request: ConnectionRequest, onRespond: (accept: Boolean, trust: Boolean) -> Unit) {
    var trust by remember(request) { mutableStateOf(false) }

    BrandDialog(onDismissRequest = { onRespond(false, false) }, dismissOnOutside = false) {
        GradientAvatar(icon = Icons.Filled.Devices, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text("連線請求", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            "「${request.requesterName}」想要與你連線",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(request.requesterAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { trust = !trust }.padding(end = 8.dp),
        ) {
            Checkbox(checked = trust, onCheckedChange = { trust = it })
            Text("信任此裝置,之後自動接受", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(onClick = { onRespond(false, false) }, text = "拒絕", modifier = Modifier.weight(1f))
            GradientButton(onClick = { onRespond(true, trust) }, text = "接受", modifier = Modifier.weight(1f))
        }
    }
}

/** 送出連線請求後、對方還沒按接受/拒絕之前顯示,可以取消。 */
@Composable
fun WaitingForAcceptDialog(deviceName: String, onCancel: () -> Unit) {
    BrandDialog(onDismissRequest = onCancel, dismissOnOutside = false) {
        RadarPulse(icon = Icons.Filled.SettingsInputAntenna, size = 130.dp)
        Spacer(Modifier.height(12.dp))
        Text("正在等待「$deviceName」接受", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "請在對方的裝置上按下「接受」。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        GhostButton(onClick = onCancel, text = "取消", modifier = Modifier.fillMaxWidth())
    }
}

/** 連線失敗時說明原因與建議(被拒絕/沒回應/連不到),而不是只有一句「未接受」。 */
@Composable
fun ConnectFailureDialog(failure: ConnectFailure, onDismiss: () -> Unit) {
    val (icon, title, message) = describeFailure(failure)
    BrandDialog(onDismissRequest = onDismiss) {
        IconTile(icon = icon, size = 56.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        GradientButton(onClick = onDismiss, text = "知道了", modifier = Modifier.fillMaxWidth())
    }
}

private data class FailureText(val icon: ImageVector, val title: String, val message: String)

private fun describeFailure(failure: ConnectFailure): FailureText = when (failure.outcome) {
    ConnectionRequestOutcome.REJECTED -> FailureText(
        Icons.Filled.PersonOff, "「${failure.deviceName}」拒絕了連線",
        "對方按下了「拒絕」。如果是誤按,可以再試一次。",
    )
    ConnectionRequestOutcome.TIMEOUT -> FailureText(
        Icons.Filled.HourglassEmpty, "「${failure.deviceName}」沒有回應",
        "對方沒有在時間內按下接受。請確認對方的畫面上有看到連線請求。",
    )
    else -> FailureText(
        Icons.Filled.LinkOff, "連不到「${failure.deviceName}」",
        "請確認對方仍開著 ConnectIt,而且兩台裝置連在同一個 Wi-Fi。也可能是對方的防火牆擋下了連線。",
    )
}
