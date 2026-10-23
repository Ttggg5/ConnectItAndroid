package com.connectit.android.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.connectit.android.screen.ScreenControlAccessibilityService
import com.connectit.android.screen.ScreenMirrorState
import com.connectit.android.service.ConnectItService
import com.connectit.android.service.ConnectionUiState
import com.connectit.android.ui.theme.BrandColors

/**
 * 螢幕鏡像的全域元件,放在畫面根部(手機版 ConnectItApp / TV 版 TvAppRoot)——不論使用者目前在
 * 哪一頁,對方送來的請求/邀請都要跳出來:
 * - 對方想看我的螢幕 / 想分享給我看的確認對話框
 * - 我送出請求/邀請後「等待對方回應」的對話框(可以取消)
 * - 需要擷取權限時,跳出系統的「開始錄製或投放」確認畫面
 */
@Composable
fun ScreenMirrorHost(service: ConnectItService) {
    val controller = service.screenMirror
    val state by controller.state.collectAsState()
    val connectionState by service.connectionState.collectAsState()
    val peerName = (connectionState as? ConnectionUiState.Connected)?.peer?.name ?: "對方"
    val context = LocalContext.current

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        service.onScreenCapturePermissionResult(result.resultCode, result.data)
    }

    // 同一個工作階段只跳一次系統確認:旋轉螢幕重建畫面時狀態還是 AwaitingProjection,不能再跳第二次。
    var launchedForSession by rememberSaveable { mutableStateOf<String?>(null) }
    val awaiting = state as? ScreenMirrorState.AwaitingProjection
    LaunchedEffect(awaiting?.sessionId) {
        val sessionId = awaiting?.sessionId ?: return@LaunchedEffect
        if (launchedForSession == sessionId) return@LaunchedEffect
        launchedForSession = sessionId
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            controller.onProjectionResult(null, unsupported = true)
            return@LaunchedEffect
        }
        try {
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: ActivityNotFoundException) {
            controller.onProjectionResult(null, unsupported = true)
        }
    }

    // 允許了對方操控、但無障礙服務還沒開:主動跳出說明並帶使用者去設定(擷取確認畫面一結束就會看到)。
    // 使用者按「稍後」就不再催,直到下次重新打開「允許對方操控」;服務一開啟對話框自動消失。
    val accessibilityRunning by ScreenControlAccessibilityService.running.collectAsState()
    val sharing = state as? ScreenMirrorState.Sharing
    var accessibilityPromptDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sharing?.sessionId, sharing?.allowControl) {
        if (sharing?.allowControl != true) accessibilityPromptDismissed = false
    }
    if (sharing != null && sharing.allowControl && !accessibilityRunning && !accessibilityPromptDismissed) {
        AccessibilityPromptDialog(onDismiss = { accessibilityPromptDismissed = true })
    }

    when (val current = state) {
        is ScreenMirrorState.IncomingRequest -> IncomingScreenRequestDialog(
            peerName = peerName,
            onRespond = { accept, allowControl -> controller.respondToIncomingRequest(accept, allowControl) },
        )
        is ScreenMirrorState.IncomingOffer -> IncomingScreenOfferDialog(
            peerName = peerName,
            onRespond = { accept -> controller.respondToIncomingOffer(accept) },
        )
        is ScreenMirrorState.OutgoingRequest -> WaitingDialog(
            title = "正在等待「$peerName」同意",
            message = "對方同意後,就會在這裡看到對方的螢幕。",
            onCancel = { controller.cancelOutgoing() },
        )
        is ScreenMirrorState.OutgoingOffer -> WaitingDialog(
            title = "正在等待「$peerName」接受",
            message = "對方接受後就會開始分享你的螢幕。",
            onCancel = { controller.cancelOutgoing() },
        )
        else -> Unit
    }
}

/**
 * 「目前連線」卡片裡的螢幕鏡像區塊:觀看對方螢幕 / 分享我的螢幕;分享中則顯示狀態、
 * 「允許對方操控」開關(含無障礙服務沒開時的提示)與停止分享。
 */
@Composable
fun ScreenMirrorSection(service: ConnectItService, modifier: Modifier = Modifier) {
    val controller = service.screenMirror
    val state by controller.state.collectAsState()
    var showShareDialog by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Text("螢幕鏡像", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        when (val current = state) {
            is ScreenMirrorState.Sharing -> SharingCard(
                state = current,
                onAllowControlChange = { controller.setAllowControl(it) },
                onStop = { controller.stop() },
            )
            else -> {
                val idle = current == ScreenMirrorState.Idle
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    GhostButton(
                        onClick = { controller.requestViewPeer() },
                        text = "觀看對方螢幕",
                        icon = Icons.Filled.CastConnected,
                        enabled = idle,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                    )
                    GhostButton(
                        onClick = { showShareDialog = true },
                        text = "分享我的螢幕",
                        icon = Icons.Filled.ScreenShare,
                        enabled = idle,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                    )
                }
            }
        }
    }

    if (showShareDialog) {
        ShareScreenDialog(
            onConfirm = { allowControl ->
                showShareDialog = false
                controller.startShareOffer(allowControl)
            },
            onDismiss = { showShareDialog = false },
        )
    }
}

@Composable
private fun SharingCard(
    state: ScreenMirrorState.Sharing,
    onAllowControlChange: (Boolean) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val accessibilityRunning by ScreenControlAccessibilityService.running.collectAsState()

    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ScreenShare, contentDescription = null, tint = BrandColors.Success)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("正在分享你的螢幕", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.controlActive) "對方可以操控這台裝置" else "對方只能觀看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("允許對方操控", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.allowControl, onCheckedChange = onAllowControlChange)
            }
            if (state.allowControl && !accessibilityRunning) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = BrandColors.Warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "要讓對方操控,請到系統的無障礙設定開啟「ConnectIt 螢幕鏡像遠端操控」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row {
                    TextButton(onClick = { ScreenControlAccessibilityService.openSettings(context) }) { Text("開啟無障礙設定") }
                    if (ScreenControlAccessibilityService.mayBeRestricted) {
                        TextButton(onClick = { ScreenControlAccessibilityService.openAppInfo(context) }) { Text("應用程式資訊") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(
                onClick = onStop,
                text = "停止分享",
                icon = Icons.Filled.StopScreenShare,
                danger = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 說明為什麼要開無障礙服務,並直接帶到設定頁;Android 13+ 側載安裝時另外說明「受限制的設定」怎麼解除。 */
@Composable
private fun AccessibilityPromptDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    MirrorDialog(onDismissRequest = onDismiss) {
        GradientAvatar(icon = Icons.Filled.TouchApp, size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text("開啟遠端操控", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "你允許了對方操控這台裝置。Android 要求先在無障礙設定裡開啟「ConnectIt 螢幕鏡像遠端操控」,對方才能點擊、滑動與打字;開啟前對方只能觀看。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (ScreenControlAccessibilityService.mayBeRestricted) {
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "如果開關是灰色的、顯示「受限制的設定」:按下方「應用程式資訊」→ 右上角 ⋮ →「允許受限制的設定」,再回來開啟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        GradientButton(
            onClick = { ScreenControlAccessibilityService.openSettings(context) },
            text = "前往無障礙設定",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(onClick = onDismiss, text = "稍後", modifier = Modifier.weight(1f))
            if (ScreenControlAccessibilityService.mayBeRestricted) {
                GhostButton(
                    onClick = { ScreenControlAccessibilityService.openAppInfo(context) },
                    text = "應用程式資訊",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MirrorDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                content()
            }
        }
    }
}

@Composable
private fun AllowControlCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onCheckedChange(!checked) }.padding(end = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column {
            Text("允許對方操控這台裝置", style = MaterialTheme.typography.bodyMedium)
            Text(
                "對方可以點擊、滑動與輸入文字。需要開啟無障礙服務。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShareScreenDialog(onConfirm: (allowControl: Boolean) -> Unit, onDismiss: () -> Unit) {
    var allowControl by remember { mutableStateOf(false) }
    MirrorDialog(onDismissRequest = onDismiss) {
        GradientAvatar(icon = Icons.Filled.ScreenShare, size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text("分享你的螢幕", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "接下來系統會詢問是否允許擷取螢幕,對方接受後就會看到你的畫面。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        AllowControlCheckbox(checked = allowControl, onCheckedChange = { allowControl = it })
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(onClick = onDismiss, text = "取消", modifier = Modifier.weight(1f))
            GradientButton(onClick = { onConfirm(allowControl) }, text = "開始分享", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun IncomingScreenRequestDialog(peerName: String, onRespond: (accept: Boolean, allowControl: Boolean) -> Unit) {
    var allowControl by remember { mutableStateOf(false) }
    MirrorDialog(onDismissRequest = { onRespond(false, false) }) {
        GradientAvatar(icon = Icons.Filled.ScreenShare, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text("螢幕鏡像", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("「$peerName」想要觀看你的螢幕", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "同意後系統會再詢問一次是否允許擷取螢幕。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        AllowControlCheckbox(checked = allowControl, onCheckedChange = { allowControl = it })
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(onClick = { onRespond(false, false) }, text = "拒絕", modifier = Modifier.weight(1f))
            GradientButton(onClick = { onRespond(true, allowControl) }, text = "同意", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun IncomingScreenOfferDialog(peerName: String, onRespond: (accept: Boolean) -> Unit) {
    MirrorDialog(onDismissRequest = { onRespond(false) }) {
        GradientAvatar(icon = Icons.Filled.CastConnected, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text("螢幕鏡像", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("「$peerName」想要分享螢幕給你", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            GhostButton(onClick = { onRespond(false) }, text = "拒絕", modifier = Modifier.weight(1f))
            GradientButton(onClick = { onRespond(true) }, text = "觀看", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun WaitingDialog(title: String, message: String, onCancel: () -> Unit) {
    MirrorDialog(onDismissRequest = onCancel) {
        RadarPulse(icon = Icons.Filled.CastConnected, size = 130.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        GhostButton(onClick = onCancel, text = "取消", modifier = Modifier.fillMaxWidth())
    }
}
