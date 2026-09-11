package com.connectit.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.connectit.android.repo.AppThemeMode

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
    secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFD0BCFF),
    secondary = androidx.compose.ui.graphics.Color(0xFFCCC2DC),
)

@Composable
fun ConnectItTheme(themeMode: AppThemeMode, content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.AUTO -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamicAvailable && useDark -> dynamicDarkColorScheme(context)
        dynamicAvailable && !useDark -> dynamicLightColorScheme(context)
        useDark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
