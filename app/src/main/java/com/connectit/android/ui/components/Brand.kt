package com.connectit.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.connectit.android.ui.theme.BrandColors
import com.connectit.android.ui.theme.LocalIsDarkTheme

/*
 * ConnectIt「Aurora」品牌元件:漸層按鈕、Hero 卡片、雷達掃描動畫、漸層頭像、狀態標籤、空狀態等,
 * 對應 Windows 端 Themes/Brand.xaml 裡的同名樣式,兩邊的畫面才會長得像同一個 App。
 */

/** 一般內容卡片:比背景亮一階的表面色 + 細邊框 + 20dp 圓角。 */
@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** 漸層背景的 Hero 卡片(「這台裝置」、「繼續觀看」),裡面的文字/圖示一律白色。 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val brush = if (LocalIsDarkTheme.current) BrandColors.HeroDark else BrandColors.HeroLight
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(brush),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** 品牌主要按鈕:漸層底、白字、圓角膠囊。 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    text: String,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier
                .background(BrandColors.Gradient)
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 次要按鈕:透明底 + 細邊框。[danger] 為 true 時文字換成紅色(中斷連線、停止分享)。 */
@Composable
fun GhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
    text: String,
) {
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 圓形漸層頭像,中間放一個圖示(裝置、影片伺服器)。 */
@Composable
fun GradientAvatar(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = CircleShape,
    iconSize: Dp = size * 0.5f,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(BrandColors.Gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** 淡色底的圓角圖示方塊(設定分組標題、說明步驟)。 */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.55f))
    }
}

/** 狀態標籤:圓角膠囊,可選擇前面加一個會「呼吸」的小圓點(表示進行中)。 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    dotColor: Color? = null,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            BreathingDot(color = dotColor)
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}

/** 在漸層 Hero 上用的半透明白色標籤。 */
@Composable
fun GlassPill(text: String, modifier: Modifier = Modifier, dotColor: Color? = null) {
    StatusPill(
        text = text,
        modifier = modifier,
        containerColor = Color.White.copy(alpha = 0.16f),
        contentColor = Color.White,
        dotColor = dotColor,
    )
}

@Composable
fun BreathingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "breathingAlpha",
    )
    Box(
        modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * 雷達掃描動畫:三道同心圓由中心往外擴散、淡出,中間是漸層圓形 + 圖示。
 * 用在「正在搜尋附近的裝置/影片伺服器」、「等待對方接受」等需要等待的畫面。
 */
@Composable
fun RadarPulse(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 160.dp) {
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "radarProgress",
    )
    val ringBrush = BrandColors.Gradient
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val maxRadius = this.size.minDimension / 2f
            repeat(3) { ring ->
                val phase = (progress + ring / 3f) % 1f
                val radius = maxRadius * (0.35f + 0.65f * phase)
                drawCircle(
                    brush = ringBrush,
                    radius = radius,
                    alpha = (1f - phase) * 0.9f,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        GradientAvatar(icon = icon, size = size * 0.4f)
    }
}

/** 置中的空狀態/等待狀態:插圖(通常是 [RadarPulse])+ 標題 + 說明。 */
@Composable
fun EmptyState(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
    illustration: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .widthIn(max = 380.dp)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        illustration()
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (description != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 區塊標題:左邊大標題,右邊可放動作(例如「重新搜尋」)。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: (@Composable RowScope.() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (supporting != null) {
            Spacer(Modifier.width(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, content = supporting)
        }
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

/** 漸層填色的進度條。 */
@Composable
fun GradientProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(BrandColors.GradientHorizontal),
        )
    }
}

/** 漸層描邊(選取中/播放中的卡片)。 */
fun Modifier.gradientBorder(width: Dp = 2.dp, shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    border(BorderStroke(width, BrandColors.Gradient), shape)
