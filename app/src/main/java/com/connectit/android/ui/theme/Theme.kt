package com.connectit.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.connectit.android.repo.AppThemeMode

/** 目前實際套用的是不是深色主題(AUTO 時依系統),給 Hero 漸層這類主題相依、又不在 ColorScheme 裡的品牌元素用。 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

@Composable
fun ConnectItTheme(themeMode: AppThemeMode, content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.AUTO -> isSystemInDarkTheme()
    }

    // 品牌色要跟 Windows 版一致,所以不用 Material You 動態色彩(會跟著裝置桌布跑掉),
    // 固定套用 Aurora 色票(見 Color.kt)。
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

    CompositionLocalProvider(LocalIsDarkTheme provides useDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BrandTypography,
            shapes = BrandShapes,
            content = content,
        )
    }
}
