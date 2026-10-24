package com.connectit.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.connectit.android.screen.RemoteControlPhase
import com.connectit.android.screen.RemoteControlState
import com.connectit.android.service.ConnectItService

/**
 * 遙控器的全域對話框,放在畫面根部(跟 [ScreenMirrorHost] 一樣,不論使用者在哪一頁都要跳出來):
 * - 對方想遙控這台裝置的確認對話框(信任的裝置會直接同意,不會出現)
 * - 我送出遙控請求後「等待對方同意」的對話框(可以取消)
 */
@Composable
fun RemoteControlHost(service: ConnectItService) {
    val state by service.remoteControl.state.collectAsState()
    val peerName = state.peer?.name ?: "對方"
    when (state.phase) {
        RemoteControlPhase.INCOMING_REQUEST -> AlertDialog(
            onDismissRequest = { service.remoteControl.respondToIncomingRequest(false) },
            icon = { Icon(Icons.Filled.SettingsRemote, contentDescription = null) },
            title = { Text("「$peerName」想要遙控這台裝置", textAlign = TextAlign.Center) },
            text = {
                Text(
                    "同意後對方可以控制這台裝置的播放/暫停、上一首/下一首與音量(不能操作畫面)。",
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = { TextButton(onClick = { service.remoteControl.respondToIncomingRequest(true) }) { Text("同意") } },
            dismissButton = { TextButton(onClick = { service.remoteControl.respondToIncomingRequest(false) }) { Text("拒絕") } },
        )
        RemoteControlPhase.OUTGOING_REQUEST -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Filled.SettingsRemote, contentDescription = null) },
            title = { Text("正在等待「$peerName」同意", textAlign = TextAlign.Center) },
            text = { Text("對方同意後就會開啟遙控器。", textAlign = TextAlign.Center) },
            confirmButton = { TextButton(onClick = { service.remoteControl.stop() }) { Text("取消") } },
        )
        else -> Unit
    }
}

/** 「目前連線」卡片裡的遙控器區塊:開啟遙控器;正在被遙控時顯示狀態與結束按鈕。 */
@Composable
fun RemoteControlSection(service: ConnectItService, modifier: Modifier = Modifier) {
    val state by service.remoteControl.state.collectAsState()
    if (state.phase == RemoteControlPhase.CONTROLLED) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SettingsRemote, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("${state.peer?.name ?: "對方"} 正在遙控這台裝置", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { service.remoteControl.stop() }) { Text("結束") }
            }
        }
    } else {
        GhostButton(
            onClick = { service.requestRemoteControl() },
            text = "遙控器(媒體鍵、音量、簡報)",
            icon = Icons.Filled.SettingsRemote,
            enabled = state.phase == RemoteControlPhase.IDLE,
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
        )
    }
}
