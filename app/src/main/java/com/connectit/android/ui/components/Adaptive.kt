package com.connectit.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 折疊機攤開、平板等寬螢幕的斷點(對應 Material 的 Medium window size class)。 */
val AdaptiveNavigationBreakpoint = 600.dp

private val MaxContentWidth = 640.dp

/**
 * 螢幕變寬(折疊機攤開、平板)時,清單/表單內容全寬鋪滿會被拉得又寬又難閱讀,
 * 所以限制最大寬度並置中;寬度不足時(手機直向、折疊機收合)照常鋪滿整個寬度,行為不變。
 */
@Composable
fun AdaptiveContentWidth(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize(), content = content)
    }
}
