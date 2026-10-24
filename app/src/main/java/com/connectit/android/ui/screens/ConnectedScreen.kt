package com.connectit.android.ui.screens

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.connectit.android.model.ConnectedPeer
import com.connectit.android.model.TransferRecord
import com.connectit.android.service.ConnectItService
import com.connectit.android.ui.components.BrandCard
import com.connectit.android.ui.components.GhostButton
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.components.GradientButton
import com.connectit.android.ui.components.GradientProgressBar
import com.connectit.android.ui.components.RemoteControlSection
import com.connectit.android.ui.components.ScreenMirrorSection
import com.connectit.android.ui.theme.BrandColors
import com.connectit.android.util.SafUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「目前連線」卡片:連線對象、傳送檔案/資料夾、傳輸進度(接收完成後可直接開啟)、這次連線的傳輸紀錄、中斷連線。
 * 以前已連線時整個「裝置」頁會換成另一個畫面、看不到其他裝置;現在這張卡片疊在裝置清單上方
 * (寬螢幕則並排在右側),連線狀態一目了然。
 */
@Composable
fun ConnectionPanel(service: ConnectItService, peer: ConnectedPeer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val transferState by service.transferState.collectAsState()
    val completed by service.completedTransfer.collectAsState()
    val history by service.transferHistory.collectAsState()
    val connectedPeers by service.connectedPeers.collectAsState()
    val multiplePeers = connectedPeers.size > 1

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

    BrandCard(modifier = modifier.fillMaxWidth()) {
        Text(
            if (multiplePeers) "目前連線(${connectedPeers.size} 台)" else "目前連線",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (multiplePeers) {
            Spacer(Modifier.height(8.dp))
            PeerSwitcher(service = service, peers = connectedPeers, selectedPeerId = peer.peerId)
        }
        Spacer(Modifier.height(14.dp))

        // 兩台裝置 + 中間的連線線條
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(BrandColors.GradientHorizontal),
                )
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = BrandColors.Success, modifier = Modifier.size(14.dp))
                }
            }
            GradientAvatar(icon = Icons.Filled.Devices, size = 48.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            peer.name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            peer.address,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(16.dp))
        val transferActive = transferState != null
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SendTile(
                icon = Icons.Filled.UploadFile,
                label = "傳送檔案",
                enabled = !transferActive,
                highlighted = true,
                onClick = { pickFiles.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            )
            SendTile(
                icon = Icons.Filled.DriveFolderUpload,
                label = "傳送資料夾",
                enabled = !transferActive,
                highlighted = false,
                onClick = { pickFolder.launch(null) },
                modifier = Modifier.weight(1f),
            )
        }
        if (multiplePeers) {
            SendToAllRow(service = service)
        }
        Text(
            "也可以在相簿或檔案 App 裡按「分享」→ ConnectIt 直接傳過來。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        ScreenMirrorSection(service = service)
        Spacer(Modifier.height(10.dp))
        RemoteControlSection(service = service)

        // 傳輸中
        transferState?.let { state ->
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        state.percentText?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { service.cancelTransfer() }) {
                            Icon(Icons.Filled.Close, contentDescription = "取消傳輸")
                        }
                    }
                    GradientProgressBar(progress = state.progress, modifier = Modifier.padding(vertical = 8.dp))
                    Text(state.statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 接收完成:直接開啟
        if (transferState == null) {
            completed?.let { record ->
                Spacer(Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandColors.Success)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                record.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { service.dismissCompletedTransfer() }) {
                                Icon(Icons.Filled.Close, contentDescription = "關閉")
                            }
                        }
                        Text(
                            "已儲存到 ${service.downloadDisplayPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        GradientButton(
                            onClick = { openTransferRecord(context, record) },
                            text = if (record.isFolder) "開啟下載資料夾" else "開啟檔案",
                            icon = if (record.isFolder) Icons.Filled.FolderOpen else Icons.AutoMirrored.Filled.OpenInNew,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        // 傳輸紀錄
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("傳輸紀錄", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            history.take(MAX_VISIBLE_HISTORY).forEach { record ->
                TransferHistoryRow(record = record, onOpen = { openTransferRecord(context, record) })
            }
        }

        Spacer(Modifier.height(16.dp))
        GhostButton(
            onClick = { service.disconnectFromPeer() },
            text = "中斷連線",
            icon = Icons.Filled.LinkOff,
            danger = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val MAX_VISIBLE_HISTORY = 8

@Composable
private fun SendTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = if (highlighted) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurface,
        border = if (highlighted) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            modifier = (if (highlighted) Modifier.background(BrandColors.Gradient) else Modifier)
                .padding(vertical = 16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TransferHistoryRow(record: TransferRecord, onOpen: () -> Unit) {
    val timeText = remember(record.timeMillis) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.timeMillis)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint, tile) = when {
            !record.succeeded -> Triple(Icons.Filled.ErrorOutline, BrandColors.Warning, MaterialTheme.colorScheme.surfaceVariant)
            record.isReceived -> Triple(Icons.Filled.ArrowDownward, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.secondaryContainer)
            else -> Triple(Icons.Filled.ArrowUpward, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (record.isFolder && record.succeeded) Icons.Filled.FolderOpen else icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(record.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(record.statusText, record.peerName, timeText).filter { it.isNotEmpty() }.joinToString("・"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (record.canOpen) {
            IconButton(onClick = onOpen) {
                Icon(
                    if (record.isFolder) Icons.Filled.FolderOpen else Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "開啟",
                )
            }
        }
    }
}

/** 用系統對應的 App 開啟收到的檔案;資料夾/多檔案則開啟系統的「下載」畫面。 */
private fun openTransferRecord(context: Context, record: TransferRecord) {
    val intent = if (record.isFolder || record.savedUri == null) {
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
    } else {
        val uri = Uri.parse(record.savedUri)
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "沒有可以開啟這個檔案的 App", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "無法開啟這個檔案,可能已經被移動或刪除", Toast.LENGTH_SHORT).show()
    }
}

/** 同時連線多台時:切換「目前連線」卡片顯示哪一台(傳送、鏡像、中斷連線都作用在選取的那台)。 */
@Composable
fun PeerSwitcher(service: ConnectItService, peers: List<ConnectedPeer>, selectedPeerId: String, modifier: Modifier = Modifier) {
    val transfers by service.peerTransfers.collectAsState()
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        peers.forEach { candidate ->
            val status = transfers[candidate.peerId]?.let { state -> state.percentText?.let { "傳輸中 $it" } ?: "準備傳輸" }
            FilterChip(
                selected = candidate.peerId == selectedPeerId,
                onClick = { service.selectPeer(candidate.peerId) },
                label = {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
            )
        }
    }
}

/** 「同時傳給所有已連線的裝置」:勾選後傳送檔案/資料夾會分別送給每一台(正在傳輸中的會略過)。 */
@Composable
private fun SendToAllRow(service: ConnectItService) {
    val sendToAll by service.sendToAll.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { service.setSendToAll(!sendToAll) },
    ) {
        Checkbox(checked = sendToAll, onCheckedChange = { service.setSendToAll(it) })
        Text("同時傳給所有已連線的裝置", style = MaterialTheme.typography.bodyMedium)
    }
}
