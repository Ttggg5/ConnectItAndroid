package com.connectit.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.connectit.android.repo.AppThemeMode

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6D83A6),
    secondary = androidx.compose.ui.graphics.Color(0xFF889CBA),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFBDC7D7),
    secondary = androidx.compose.ui.graphics.Color(0xFFC9D2E0),
)

@Composable
fun ConnectItTheme(themeMode: AppThemeMode, content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.AUTO -> isSystemInDarkTheme()
    }

    // 品牌色要跟 Windows 版一致,所以不用 Material You 動態色彩(會跟著裝置桌布跑掉),
    // 固定套用 LightColors/DarkColors。
    val colorScheme = if (useDark) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        // 應用內可自行切換淺色/深色(跟系統設定無關),所以系統列的圖示對比也要跟著這個
        // 實際套用的主題走,而不是只在啟動當下套一次系統預設值。
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDark
            insetsController.isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
