package com.connectit.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.connectit.android.repo.SettingsValidation
import com.connectit.android.ui.theme.BrandColors
import kotlinx.coroutines.delay

/*
 * 設定頁的共用元件:分組卡片、開關列、可點擊列、自動儲存的輸入框。
 * 所有變更都是立即儲存(沒有「儲存」按鈕),儲存後在分組標題右側閃一下「已儲存」。
 */

/** 停止輸入多久之後才驗證/儲存:避免打到一半(例如想輸入 30 先打了 3)就跳出錯誤。 */
private const val AUTO_SAVE_DEBOUNCE_MS = 600L

/** 一組設定:左上角圖示 + 標題,右上角在 [savedTick] 改變時閃一下「已儲存」。 */
@Composable
fun SettingsGroup(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    description: String? = null,
    savedTick: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    BrandCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = icon)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            SavedBadge(savedTick)
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.size(6.dp))
        content()
    }
}

/** [tick] 每變一次(非 0)就顯示「已儲存 ✓」約 1.6 秒。 */
@Composable
private fun SavedBadge(tick: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        visible = true
        delay(1_600)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BrandColors.Success, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("已儲存", style = MaterialTheme.typography.labelMedium, color = BrandColors.Success)
        }
    }
}

/** 給 [SettingsGroup] 用的「已儲存」計數器:每次呼叫 [bump] 讓徽章閃一下。 */
class SavedTicker {
    var value by mutableIntStateOf(0)
        private set

    fun bump() {
        value++
    }
}

@Composable
fun rememberSavedTicker() = remember { SavedTicker() }

@Composable
fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 2.dp))
}

/** 標題 + 說明 + 開關,整列都可以點。 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 標題 + 目前的值(或說明),右側箭頭,點了開選單/頁面。 */
@Composable
fun ClickRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 數字輸入框:停止輸入一下就自動驗證、合法就儲存([onSave]),不合法在下方顯示原因。
 * [initialValue] 是目前生效的值;外部值改變(例如第一次從 DataStore 讀出來)時會同步回輸入框。
 */
@Composable
fun AutoSaveNumberField(
    label: String,
    initialValue: String,
    validate: (String) -> SettingsValidation.Result,
    onSave: (Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(text, dirty) {
        if (!dirty) return@LaunchedEffect
        delay(AUTO_SAVE_DEBOUNCE_MS)
        val result = validate(text)
        error = result.error
        if (result.isValid) {
            onSave(result.value)
            dirty = false
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }
            dirty = true
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supportingText)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 自由文字輸入框:停止輸入一下就自動儲存(沒有不合法的狀態,例如副檔名清單)。 */
@Composable
fun AutoSaveTextField(
    label: String,
    initialValue: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    validate: ((String) -> String?)? = null,
    placeholder: String? = null,
    supportingText: String? = null,
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(text, dirty) {
        if (!dirty) return@LaunchedEffect
        delay(AUTO_SAVE_DEBOUNCE_MS)
        error = validate?.invoke(text)
        if (error == null) {
            onSave(text)
            dirty = false
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            dirty = true
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supportingText)?.let { { Text(it) } },
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 小標題(分組內的子區塊)。 */
@Composable
fun SettingsSubheader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** 一列並排的按鈕(例如「變更」「還原預設」)。 */
@Composable
fun SettingsButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier = modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
