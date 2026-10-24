package com.connectit.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TvOff
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import com.connectit.android.net.RemoteKeys

/** 遙控器/螢幕鏡像快捷鍵列上的一顆按鈕:依序按下 [keys],再反過來放開。 */
data class RemoteShortcut(val label: String, val icon: ImageVector, val keys: List<String>)

/**
 * 遙控器畫面與螢幕鏡像觀看畫面共用的按鈕清單(對應 Windows 端 RemoteShortcuts.cs)。媒體鍵兩種受控端都能用;
 * 簡報與系統快捷鍵只有 Windows 受控端能用(Android 不允許 App 注入任意按鍵)。
 */
object RemoteShortcuts {
    val media = listOf(
        RemoteShortcut("上一首", Icons.Filled.SkipPrevious, listOf(RemoteKeys.PREVIOUS)),
        RemoteShortcut("播放/暫停", Icons.Filled.PlayArrow, listOf(RemoteKeys.PLAY_PAUSE)),
        RemoteShortcut("下一首", Icons.Filled.SkipNext, listOf(RemoteKeys.NEXT)),
        RemoteShortcut("停止", Icons.Filled.Stop, listOf(RemoteKeys.STOP)),
        RemoteShortcut("音量−", Icons.AutoMirrored.Filled.VolumeDown, listOf(RemoteKeys.VOLUME_DOWN)),
        RemoteShortcut("靜音", Icons.AutoMirrored.Filled.VolumeOff, listOf(RemoteKeys.VOLUME_MUTE)),
        RemoteShortcut("音量+", Icons.AutoMirrored.Filled.VolumeUp, listOf(RemoteKeys.VOLUME_UP)),
    )

    val presentation = listOf(
        RemoteShortcut("上一頁", Icons.Filled.ChevronLeft, listOf("ArrowLeft")),
        RemoteShortcut("下一頁", Icons.Filled.ChevronRight, listOf("ArrowRight")),
        RemoteShortcut("開始投影片", Icons.Filled.Slideshow, listOf("F5")),
        RemoteShortcut("黑畫面", Icons.Filled.TvOff, listOf("b")),
        RemoteShortcut("結束", Icons.AutoMirrored.Filled.ExitToApp, listOf("Escape")),
    )

    val system = listOf(
        RemoteShortcut("複製", Icons.Filled.ContentCopy, listOf("Control", "c")),
        RemoteShortcut("貼上", Icons.Filled.ContentPaste, listOf("Control", "v")),
        RemoteShortcut("復原", Icons.AutoMirrored.Filled.Undo, listOf("Control", "z")),
        RemoteShortcut("Alt+Tab", Icons.Filled.SwapHoriz, listOf("Alt", "Tab")),
        RemoteShortcut("開始(Win)", Icons.Filled.GridView, listOf("Meta")),
        RemoteShortcut("顯示桌面", Icons.Filled.DesktopWindows, listOf("Meta", "d")),
        RemoteShortcut("Alt+F4", Icons.Filled.Close, listOf("Alt", "F4")),
    )
}
