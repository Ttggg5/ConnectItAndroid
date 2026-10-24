package com.connectit.android.ui.tv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.connectit.android.model.ConnectedPeer
import com.connectit.android.screen.ScreenAudioFormat
import com.connectit.android.screen.ScreenMirrorState
import com.connectit.android.service.ConnectItService
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.components.GradientProgressBar
import com.connectit.android.ui.theme.BrandColors
import com.connectit.android.util.SafUtils

/** TV 版「目前連線」面板:連線對象、傳送檔案/資料夾、傳輸進度、這次連線的傳輸紀錄、中斷連線。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvConnectionPanel(service: ConnectItService, peer: ConnectedPeer) {
    val context = LocalContext.current
    val transferState by service.transferState.collectAsState()
    val history by service.transferHistory.collectAsState()
    val screenMirrorState by service.screenMirror.state.collectAsState()
    val connectedPeers by service.connectedPeers.collectAsState()
    val peerTransfers by service.peerTransfers.collectAsState()
    val sendToAll by service.sendToAll.collectAsState()

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val files = uris.map { uri ->
            val (name, size) = SafUtils.queryNameAndSize(context.contentResolver, uri)
            Triple(uri, name, size)
        }
        service.sendFiles(files)
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val entries = SafUtils.enumerateTree(context, treeUri)
        service.sendFolder(SafUtils.displayNameOf(context, treeUri), entries)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientAvatar(icon = Icons.Filled.Devices, size = 56.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (connectedPeers.size > 1) "目前連線(${connectedPeers.size} 台)" else "目前連線",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(peer.name, style = MaterialTheme.typography.headlineSmall)
                    Text(peer.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandColors.Success)
                Spacer(Modifier.width(8.dp))
                Text("已連線", style = MaterialTheme.typography.titleMedium, color = BrandColors.Success)
            }

            if (connectedPeers.size > 1) {
                // 同時連線多台時:切換這張卡片顯示哪一台(傳送、鏡像、中斷連線都作用在選取的那台)。
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    connectedPeers.forEach { candidate ->
                        val label = peerTransfers[candidate.peerId]?.percentText?.let { "${candidate.name}・$it" } ?: candidate.name
                        if (candidate.peerId == peer.peerId) {
                            Button(onClick = {}) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { service.selectPeer(candidate.peerId) }) { Text(label) }
                        }
                    }
                }
                OutlinedButton(onClick = { service.setSendToAll(!sendToAll) }) {
                    Text(if (sendToAll) "✓ 同時傳給所有已連線的裝置" else "同時傳給所有已連線的裝置")
                }
            }

            val transferActive = transferState != null
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = !transferActive) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("傳送檔案")
                }
                Button(onClick = { pickFolder.launch(null) }, enabled = !transferActive) {
                    Icon(Icons.Filled.DriveFolderUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("傳送資料夾")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { service.disconnectFromPeer() }) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("中斷連線")
                }
            }

            // 螢幕鏡像:TV 主動分享時只能觀看(遙控器不方便確認操控設定);對方請求觀看時的對話框
            // 仍可以勾選允許操控(見 ScreenMirrorHost)。
            val sharing = screenMirrorState as? ScreenMirrorState.Sharing
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sharing != null) {
                    Icon(Icons.Filled.ScreenShare, contentDescription = null, tint = BrandColors.Success)
                    Text(
                        if (sharing.controlActive) "正在分享螢幕(對方可以操控)" else "正在分享螢幕",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = { service.screenMirror.stop() }) {
                        Icon(Icons.Filled.StopScreenShare, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("停止分享")
                    }
                } else {
                    val idle = screenMirrorState == ScreenMirrorState.Idle
                    OutlinedButton(onClick = { service.requestViewPeerScreen() }, enabled = idle) {
                        Icon(Icons.Filled.CastConnected, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("觀看對方螢幕")
                    }
                    OutlinedButton(onClick = { service.startScreenShareOffer(allowControl = false, shareAudio = ScreenAudioFormat.captureSupported) }, enabled = idle) {
                        Icon(Icons.Filled.ScreenShare, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("分享我的螢幕")
                    }
                }
            }

            transferState?.let { state ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        state.percentText?.let {
                            Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                        }
                        OutlinedButton(onClick = { service.cancelTransfer() }) { Text("取消") }
                    }
                    GradientProgressBar(progress = state.progress, height = 10.dp)
                    Text(state.statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (history.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("這次連線的傳輸紀錄", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    history.take(5).forEach { record ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (icon, tint) = when {
                                !record.succeeded -> Icons.Filled.ErrorOutline to BrandColors.Warning
                                record.isReceived -> Icons.Filled.ArrowDownward to MaterialTheme.colorScheme.secondary
                                else -> Icons.Filled.ArrowUpward to MaterialTheme.colorScheme.primary
                            }
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(record.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(record.statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
