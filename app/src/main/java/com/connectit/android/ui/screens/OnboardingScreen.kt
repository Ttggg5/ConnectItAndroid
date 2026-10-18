package com.connectit.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.connectit.android.repo.SettingsValidation
import com.connectit.android.ui.components.BrandCard
import com.connectit.android.ui.components.GhostButton
import com.connectit.android.ui.components.GradientAvatar
import com.connectit.android.ui.components.GradientButton
import com.connectit.android.ui.components.IconTile
import com.connectit.android.ui.components.RadarPulse
import com.connectit.android.ui.theme.BrandColors

private const val StepCount = 3

/**
 * 首次啟動引導(對應 Windows 端 MainWindow.Onboarding.cs):歡迎 → 替裝置取名 → 怎麼使用(順便請求通知權限)。
 * [onFinish] 帶回使用者取的裝置名稱;按「略過」時帶回 null(沿用目前的名稱)。
 */
@Composable
fun OnboardingScreen(initialDeviceName: String, onFinish: (deviceName: String?) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable(initialDeviceName) { mutableStateOf(initialDeviceName) }
    val nameError = SettingsValidation.validateDeviceName(name)
    // 電視遙控器(D-pad)一進畫面就能直接按 OK 前進:預設把焦點放在「下一步」;取名那一步則交給輸入框。
    val nextFocus = remember { FocusRequester() }
    LaunchedEffect(step) {
        if (step != 1) runCatching { nextFocus.requestFocus() }
    }

    fun next() {
        if (step == 1 && nameError != null) return
        if (step < StepCount - 1) step++ else onFinish(name.trim())
    }

    // 用 Surface 而不是單純畫背景:Surface 會一併提供文字的預設顏色,Android TV 版直接顯示這個畫面
    // (外面沒有手機版那層 Surface)時文字才不會變成預設的黑色。
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "onboardingStep",
                ) { current ->
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> NameStep(name = name, error = nameError, onNameChange = { name = it }, onDone = ::next)
                        else -> HowToStep()
                    }
                }

                Spacer(Modifier.height(32.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (step == 0) onFinish(null) else step-- }) {
                        Text(if (step == 0) "略過" else "上一步")
                    }
                    Spacer(Modifier.weight(1f))
                    StepDots(current = step)
                    Spacer(Modifier.weight(1f))
                    GradientButton(
                        onClick = ::next,
                        enabled = step != 1 || nameError == null,
                        text = if (step == StepCount - 1) "開始使用" else "下一步",
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(StepCount) { index ->
            val active = index == current
            Box(
                Modifier
                    .height(8.dp)
                    .width(if (active) 22.dp else 8.dp)
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (index <= current) Modifier.background(BrandColors.Gradient)
                        else Modifier.background(MaterialTheme.colorScheme.outlineVariant)
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RadarPulse(icon = Icons.Filled.Hub, size = 170.dp)
        Spacer(Modifier.height(20.dp))
        Text("歡迎使用 ConnectIt", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "在同一個區網內,讓手機和電腦自動找到彼此、互相傳送檔案,或把影片分享給其他裝置觀看。不需要帳號,也不經過網路雲端。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NameStep(name: String, error: String?, onNameChange: (String) -> Unit, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GradientAvatar(icon = Icons.Filled.PhoneAndroid, size = 88.dp, shape = RoundedCornerShape(28.dp))
        Spacer(Modifier.height(20.dp))
        Text("替這台裝置取個名字", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "其他裝置會用這個名字找到你,之後隨時可以在「裝置」頁修改。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { onNameChange(it.take(SettingsValidation.MAX_DEVICE_NAME_LENGTH)) },
            label = { Text("裝置名稱") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HowToStep() {
    val context = LocalContext.current
    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notificationsGranted by rememberSaveable {
        mutableStateOf(
            !needsNotificationPermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("三個步驟開始使用", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        HowToRow(Icons.Filled.Wifi, "連到同一個網路", "手機和電腦連同一個 Wi-Fi,兩邊都打開 ConnectIt。")
        HowToRow(Icons.Filled.Link, "找到對方、按「連線」", "對方會自動出現在「裝置」頁,對方按下接受就連上了。")
        HowToRow(Icons.Filled.UploadFile, "選擇檔案就能傳送", "也可以在相簿裡按「分享」→ ConnectIt。想看影片?到「影片」頁。")

        if (!notificationsGranted) {
            Spacer(Modifier.height(12.dp))
            BrandCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(icon = Icons.Filled.NotificationsActive)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "允許通知,才能在 App 不在前景時收到連線請求與傳輸提醒。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                GhostButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    text = "允許通知",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HowToRow(icon: ImageVector, title: String, description: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon = icon, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
