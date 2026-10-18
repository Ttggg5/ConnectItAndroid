package com.connectit.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * ConnectIt「Aurora」品牌色票:靛藍(主色)→ 青色(強調色)漸層,深色模式為主角。
 * Windows 端(Themes/Brand*.xaml)、Android TV(ui/tv/TvTheme.kt)與影片網站 CSS(VideoHostPages.kt /
 * VideoStreamingService.cs)使用同一組色票,改色時要一起改。
 */
object BrandColors {
    val Indigo = Color(0xFF6366F1)
    val IndigoDeep = Color(0xFF4F46E5)
    val IndigoLight = Color(0xFF818CF8)
    val Cyan = Color(0xFF22D3EE)
    val CyanDeep = Color(0xFF0891B2)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)

    val DarkBackground = Color(0xFF0B1020)
    val DarkSurface = Color(0xFF121A2E)
    val DarkSurfaceVariant = Color(0xFF1A2440)
    val DarkSurfaceHigh = Color(0xFF1F2A4A)
    val DarkOutline = Color(0xFF26314F)
    val DarkText = Color(0xFFE6E9F5)
    val DarkTextMuted = Color(0xFF8B93B0)

    val LightBackground = Color(0xFFF5F7FF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFEEF1FB)
    val LightOutline = Color(0xFFDDE2F0)
    val LightText = Color(0xFF111827)
    val LightTextMuted = Color(0xFF5B6478)

    /** 品牌主漸層(135°),用在主要按鈕、選取中的導覽項目、頭像、雷達等。 */
    val Gradient: Brush = Brush.linearGradient(listOf(Indigo, Cyan))

    /** 水平方向的品牌漸層,用在進度條/時間軸。 */
    val GradientHorizontal: Brush = Brush.horizontalGradient(listOf(Indigo, Cyan))

    /** 深色模式 Hero 卡片背景:較暗的靛藍 → 深青,白字仍然清楚。 */
    val HeroDark: Brush = Brush.linearGradient(listOf(Color(0xFF2B2F7A), Color(0xFF16365A), Color(0xFF0E3F4F)))

    /** 淺色模式 Hero 卡片背景:鮮明的品牌漸層。 */
    val HeroLight: Brush = Brush.linearGradient(listOf(Indigo, Color(0xFF4F7FEA), Color(0xFF22B8D6)))
}

internal val LightColors = lightColorScheme(
    primary = BrandColors.IndigoDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E7FD),
    onPrimaryContainer = Color(0xFF1E1B6B),
    secondary = BrandColors.CyanDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF6FB),
    onSecondaryContainer = Color(0xFF053B47),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    background = BrandColors.LightBackground,
    onBackground = BrandColors.LightText,
    surface = BrandColors.LightSurface,
    onSurface = BrandColors.LightText,
    surfaceVariant = BrandColors.LightSurfaceVariant,
    onSurfaceVariant = BrandColors.LightTextMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFF),
    surfaceContainer = Color(0xFFF2F4FD),
    surfaceContainerHigh = Color(0xFFECEFFB),
    surfaceContainerHighest = Color(0xFFE5E9F8),
    outline = Color(0xFFC3CADD),
    outlineVariant = BrandColors.LightOutline,
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    inverseSurface = Color(0xFF1A2440),
    inverseOnSurface = BrandColors.DarkText,
    inversePrimary = BrandColors.IndigoLight,
    scrim = Color(0xFF101828),
)

internal val DarkColors = darkColorScheme(
    primary = BrandColors.IndigoLight,
    onPrimary = Color(0xFF14123F),
    primaryContainer = Color(0xFF26284F),
    onPrimaryContainer = Color(0xFFDCDDFF),
    secondary = BrandColors.Cyan,
    onSecondary = Color(0xFF032A33),
    secondaryContainer = Color(0xFF12323F),
    onSecondaryContainer = Color(0xFFBDF3FC),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF1E0B4B),
    background = BrandColors.DarkBackground,
    onBackground = BrandColors.DarkText,
    surface = BrandColors.DarkBackground,
    onSurface = BrandColors.DarkText,
    surfaceVariant = BrandColors.DarkSurfaceVariant,
    onSurfaceVariant = BrandColors.DarkTextMuted,
    surfaceContainerLowest = Color(0xFF080C19),
    surfaceContainerLow = Color(0xFF0F1628),
    surfaceContainer = BrandColors.DarkSurface,
    surfaceContainerHigh = BrandColors.DarkSurfaceVariant,
    surfaceContainerHighest = BrandColors.DarkSurfaceHigh,
    outline = Color(0xFF3A4668),
    outlineVariant = BrandColors.DarkOutline,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF3B1219),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = BrandColors.DarkText,
    inverseOnSurface = BrandColors.DarkBackground,
    inversePrimary = BrandColors.IndigoDeep,
    scrim = Color(0xFF000000),
)
