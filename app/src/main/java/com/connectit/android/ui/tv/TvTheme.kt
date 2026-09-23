package com.connectit.android.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.connectit.android.repo.AppThemeMode

private val TvLightColors = lightColorScheme(
    primary = Color(0xFF6D83A6),
    secondary = Color(0xFF889CBA),
)

private val TvDarkColors = darkColorScheme(
    primary = Color(0xFFBDC7D7),
    secondary = Color(0xFFC9D2E0),
)

/** TV 版用 androidx.tv.material3(跟手機版的 androidx.compose.material3 是兩套獨立元件庫,
 * 焦點高亮、卡片縮放等 10-foot UI 行為都靠這套元件內建)。TV 沒有 Material You 動態色彩這回事,
 * 客廳環境多半偏暗,AUTO 在 TV 上直接當深色處理,不會因為「跟隨系統」在這裡變成刺眼的白底。 */
@Composable
fun ConnectItTvTheme(themeMode: AppThemeMode, content: @Composable () -> Unit) {
    val colorScheme = if (themeMode == AppThemeMode.LIGHT) TvLightColors else TvDarkColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
