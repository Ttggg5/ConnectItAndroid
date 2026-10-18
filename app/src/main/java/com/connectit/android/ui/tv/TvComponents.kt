package com.connectit.android.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.connectit.android.ui.components.RadarPulse
import com.connectit.android.ui.theme.BrandColors

/*
 * TV 版共用的品牌元件與焦點樣式:焦點移到卡片上時套用品牌漸層描邊 + 微微放大,
 * 讓 10 呎外也看得清楚目前選到哪一個;Hero/空狀態等跟手機版同一套視覺語言。
 */

private val TvCardShape = RoundedCornerShape(18.dp)

/** tv Card 的焦點描邊:平常是細邊框,取得焦點時換成 3dp 品牌漸層。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun tvBrandCardBorder(): CardBorder = CardDefaults.border(
    border = Border(BorderStroke(1.dp, MaterialTheme.colorScheme.borderVariant), shape = TvCardShape),
    focusedBorder = Border(BorderStroke(3.dp, BrandColors.Gradient), shape = TvCardShape),
)

@OptIn(ExperimentalTvMaterial3Api::class)
fun tvBrandCardScale(): CardScale = CardDefaults.scale(focusedScale = 1.04f)

/** tv 可點擊 Surface 的焦點描邊(列表項目、按鈕狀的大區塊)。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun tvBrandSurfaceBorder(): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(
    border = Border(BorderStroke(1.dp, MaterialTheme.colorScheme.borderVariant), shape = TvCardShape),
    focusedBorder = Border(BorderStroke(3.dp, BrandColors.Gradient), shape = TvCardShape),
)

@OptIn(ExperimentalTvMaterial3Api::class)
fun tvBrandSurfaceScale(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f)

/** 漸層 Hero 區塊(TV 一律用深色版漸層,客廳環境比較舒服)。 */
@Composable
fun TvHero(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 28.dp, vertical = 22.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(BrandColors.HeroDark),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** 置中的空狀態/等待狀態:雷達動畫 + 標題 + 說明。 */
@Composable
fun TvEmptyState(icon: ImageVector, title: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RadarPulse(icon = icon, size = 180.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (description != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 頁面大標題 + 副標題。 */
@Composable
fun TvPageTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun tvFractionOf(position: Long, duration: Long): Float =
    if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
