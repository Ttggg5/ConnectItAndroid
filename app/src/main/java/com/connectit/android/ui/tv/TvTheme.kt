package com.connectit.android.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.connectit.android.repo.AppThemeMode
import com.connectit.android.ui.theme.BrandColors

/** TV 版也用同一組「Aurora」品牌色票(見 ui/theme/Color.kt),跟手機版、Windows 版長得一樣。 */
private val TvLightColors = lightColorScheme(
    primary = BrandColors.IndigoDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E7FD),
    onPrimaryContainer = Color(0xFF1E1B6B),
    secondary = BrandColors.CyanDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF6FB),
    onSecondaryContainer = Color(0xFF053B47),
    background = BrandColors.LightBackground,
    onBackground = BrandColors.LightText,
    surface = BrandColors.LightSurface,
    onSurface = BrandColors.LightText,
    surfaceVariant = BrandColors.LightSurfaceVariant,
    onSurfaceVariant = BrandColors.LightTextMuted,
    border = Color(0xFFC3CADD),
    borderVariant = BrandColors.LightOutline,
    error = Color(0xFFDC2626),
    onError = Color.White,
)

private val TvDarkColors = darkColorScheme(
    primary = BrandColors.IndigoLight,
    onPrimary = Color(0xFF14123F),
    primaryContainer = Color(0xFF26284F),
    onPrimaryContainer = Color(0xFFDCDDFF),
    secondary = BrandColors.Cyan,
    onSecondary = Color(0xFF032A33),
    secondaryContainer = Color(0xFF12323F),
    onSecondaryContainer = Color(0xFFBDF3FC),
    background = BrandColors.DarkBackground,
    onBackground = BrandColors.DarkText,
    surface = BrandColors.DarkSurface,
    onSurface = BrandColors.DarkText,
    surfaceVariant = BrandColors.DarkSurfaceVariant,
    onSurfaceVariant = BrandColors.DarkTextMuted,
    inverseSurface = BrandColors.DarkText,
    inverseOnSurface = BrandColors.DarkBackground,
    border = Color(0xFF3A4668),
    borderVariant = BrandColors.DarkOutline,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

/** TV 版用 androidx.tv.material3(跟手機版的 androidx.compose.material3 是兩套獨立元件庫,
 * 焦點高亮、卡片縮放等 10-foot UI 行為都靠這套元件內建)。TV 沒有 Material You 動態色彩這回事,
 * 客廳環境多半偏暗,AUTO 在 TV 上直接當深色處理,不會因為「跟隨系統」在這裡變成刺眼的白底。 */
@Composable
fun ConnectItTvTheme(themeMode: AppThemeMode, content: @Composable () -> Unit) {
    val colorScheme = if (themeMode == AppThemeMode.LIGHT) TvLightColors else TvDarkColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
