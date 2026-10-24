package com.connectit.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallToAction
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.connectit.android.net.ScreenInputAction
import com.connectit.android.net.RemoteKeys
import com.connectit.android.ui.components.RemoteShortcuts
import com.connectit.android.net.ScreenMessage
import com.connectit.android.screen.ScreenMirrorState
import com.connectit.android.service.ConnectItService
import kotlin.math.abs
import kotlin.math.min

/** 遠端是電腦時,手指在螢幕上滑多少像素算捲動一格滾輪。 */
private const val PIXELS_PER_WHEEL_NOTCH = 48f

/** 按著拖曳時送出移動事件的最小間隔,太密集只會塞爆連線。 */
private const val MOVE_THROTTLE_MS = 16L

/** 隱藏輸入框裡固定保留的一個字元:刪掉它就代表使用者按了退格鍵(軟體鍵盤不一定會送出退格的按鍵事件)。 */
private const val KEYBOARD_SENTINEL = " "

/** 觸控板模式的選擇在 App 這次執行期間記住(下次觀看沿用),預設是觸控板。 */
private object ViewerPreferences {
    var touchpadMode = true
}

/** 觸控板模式:手指移動多少像素(相對於畫面寬度)= 游標移動多少;快速滑動時再乘上加速倍率。 */
private const val TOUCHPAD_BASE_GAIN = 1.0f

/** 觸控板手勢結束後這段時間內,不用分享端回報的游標位置覆蓋本機的(那些回報是我們自己移動之前的舊位置)。 */
private const val REMOTE_CURSOR_SYNC_DELAY_MS = 500L

/**
 * 觸控板模式的手勢狀態,在觸控處理(pointerInput)與畫面之間共用:手指按著時不接受分享端回報的游標位置,
 * 避免游標被拉回舊位置。
 */
private class TouchpadSync {
    @Volatile var gestureActive = false
    @Volatile var lastGestureEndedAt = 0L
}

/**
 * 觀看對方的螢幕(全螢幕)。對方允許操控時:
 * - 對方是 Android:手指的觸控原樣轉送(點擊、長按、滑動),下方有返回/主畫面/最近使用。
 * - 對方是電腦,預設「觸控板模式」(跟筆電觸控板一樣):單指滑動移動游標、點一下 = 左鍵、兩指點一下 = 右鍵、
 *   兩指上下滑 = 滾輪、長按後拖曳 = 按住左鍵拖曳。
 *   也可以切成「直接點按模式」:點哪裡就按哪裡、長按 = 右鍵、拖曳 = 按住左鍵拖曳、兩指上下滑 = 滾輪。
 *   下方有常用按鍵列。
 * 鍵盤按鈕會叫出軟體鍵盤打字。
 */
@Composable
fun ScreenViewerScreen(
    service: ConnectItService,
    state: ScreenMirrorState.Viewing,
    peerName: String,
    allowInput: Boolean = true,
) {
    val controller = service.screenMirror
    val frame by controller.frame.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val canControl = allowInput && state.allowControl
    val isRemoteAndroid = state.platform == "android"

    var overlayVisible by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(controller.audioMuted) }
    var touchpadMode by remember { mutableStateOf(ViewerPreferences.touchpadMode) }
    // 觸控板模式下游標的位置(0~1 相對座標),從畫面中央開始,之後跟分享端回報的真正游標位置同步。
    // 觸控處理直接讀寫這個 State 物件(不能經過 rememberUpdatedState:那要等下一次重組才會更新,
    // 觸控事件比畫面更新還密集,中間的移動會被舊的位置蓋掉)。
    val cursorState = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val touchpadSync = remember { TouchpadSync() }
    LaunchedEffect(state.cursorX, state.cursorY) {
        val x = state.cursorX ?: return@LaunchedEffect
        val y = state.cursorY ?: return@LaunchedEffect
        if (touchpadSync.gestureActive || SystemClock.uptimeMillis() - touchpadSync.lastGestureEndedAt < REMOTE_CURSOR_SYNC_DELAY_MS) {
            return@LaunchedEffect
        }
        cursorState.value = Offset(x.toFloat().coerceIn(0f, 1f), y.toFloat().coerceIn(0f, 1f))
    }
    val haptics = LocalHapticFeedback.current
    val useTouchpad = canControl && !isRemoteAndroid && touchpadMode
    var keyboardText by remember { mutableStateOf(TextFieldValue(KEYBOARD_SENTINEL, TextRange(KEYBOARD_SENTINEL.length))) }
    val keyboardFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler { controller.stop() }

    // 全螢幕 + 畫面常亮;對方是橫向畫面(電腦)時轉成橫向比較好看。離開時還原。
    DisposableEffect(activity, state.width > state.height) {
        view.keepScreenOn = true
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        if (state.width > state.height) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            view.keepScreenOn = false
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun send(action: String, x: Double? = null, y: Double? = null, button: Int? = null, deltaY: Double? = null, key: String? = null, text: String? = null) {
        controller.sendInput(ScreenMessage(ScreenMessage.INPUT, action = action, x = x, y = y, button = button, deltaY = deltaY, key = key, text = text))
    }

    fun pressKey(key: String) {
        send(ScreenInputAction.KEY_DOWN, key = key)
        send(ScreenInputAction.KEY_UP, key = key)
    }

    /** 組合鍵:依序按下,再反過來依序放開(Ctrl+C、Alt+Tab…)。 */
    fun pressShortcut(keys: List<String>) {
        keys.forEach { send(ScreenInputAction.KEY_DOWN, key = it) }
        keys.asReversed().forEach { send(ScreenInputAction.KEY_UP, key = it) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        val currentFrame = frame
        val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }

        // 畫面在畫布上實際佔的範圍(等比例縮放置中),觸控座標要先扣掉黑邊才能換算成 0~1。
        val imageWidth = currentFrame?.bitmap?.width ?: state.width
        val imageHeight = currentFrame?.bitmap?.height ?: state.height
        val fit = remember(canvasSize, imageWidth, imageHeight) { fitRect(canvasSize, imageWidth, imageHeight) }
        val latestFit by rememberUpdatedState(fit)
        val toggleOverlay by rememberUpdatedState { overlayVisible = !overlayVisible }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(canControl, isRemoteAndroid, useTouchpad) {
                    if (!canControl) {
                        detectSingleTaps { toggleOverlay() }
                        return@pointerInput
                    }
                    val normalize: (Offset) -> Pair<Double, Double>? = { offset -> normalize(offset, latestFit) }
                    if (isRemoteAndroid) {
                        handleTouchForwarding(normalize, ::send)
                    } else if (useTouchpad) {
                        handleTouchpad(
                            fit = { latestFit },
                            cursor = { cursorState.value },
                            moveCursor = { cursorState.value = it },
                            sync = touchpadSync,
                            longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis,
                            touchSlop = viewConfiguration.touchSlop,
                            onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            send = ::send,
                        )
                    } else {
                        handleTouchAsMouse(normalize, viewConfiguration.longPressTimeoutMillis, viewConfiguration.touchSlop, ::send)
                    }
                },
        ) {
            canvasSize = IntSize(size.width.toInt(), size.height.toInt())
            val bitmap = currentFrame?.bitmap ?: return@Canvas
            if (bitmap.isRecycled) return@Canvas
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(bitmap, null, fit, paint)
            }
        }

        if (currentFrame == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.size(12.dp))
                Text("正在等待「$peerName」的畫面…", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // 隱藏的輸入框:接收軟體鍵盤打的字,轉成 text / Backspace / Enter 送給對方。
        if (canControl) {
            BasicTextField(
                value = keyboardText,
                onValueChange = { value ->
                    // 注音、倉頡等輸入法選字中(組字區還沒確定)時先不送,等使用者選好字、組字結束才把確定的文字送出去。
                    if (value.composition != null) {
                        keyboardText = value
                        return@BasicTextField
                    }
                    val typed = value.text
                    when {
                        typed.length < KEYBOARD_SENTINEL.length -> pressKey("Backspace")
                        typed.startsWith(KEYBOARD_SENTINEL) && typed.length > KEYBOARD_SENTINEL.length ->
                            send(ScreenInputAction.TEXT, text = typed.substring(KEYBOARD_SENTINEL.length))
                    }
                    keyboardText = TextFieldValue(KEYBOARD_SENTINEL, TextRange(KEYBOARD_SENTINEL.length))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { pressKey("Enter") }),
                modifier = Modifier.size(1.dp).focusRequester(keyboardFocus),
            )
        }

        if (overlayVisible) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                ViewerTopBar(
                    peerName = peerName,
                    state = state,
                    canControl = canControl,
                    touchpadMode = if (canControl && !isRemoteAndroid) touchpadMode else null,
                    onToggleTouchpad = {
                        touchpadMode = !touchpadMode
                        ViewerPreferences.touchpadMode = touchpadMode
                    },
                    onKeyboard = {
                        keyboardFocus.requestFocus()
                        keyboardController?.show()
                    },
                    muted = if (state.audioSampleRate != null) muted else null,
                    onToggleMute = {
                        muted = !muted
                        controller.audioMuted = muted
                    },
                    onHide = { overlayVisible = false },
                    onStop = { controller.stop() },
                )
                Spacer(Modifier.weight(1f))
                if (canControl) {
                    if (isRemoteAndroid) {
                        AndroidNavBar(onNav = { send(ScreenInputAction.NAV, key = it) }, onShortcut = ::pressShortcut)
                    } else {
                        DesktopKeyBar(onKey = ::pressKey, onShortcut = ::pressShortcut)
                    }
                }
            }
        } else {
            // 隱藏工具列後,右上角留一個小點可以叫回來(畫面本身的點擊要轉送給對方,不能拿來切換)。
            Surface(
                onClick = { overlayVisible = true },
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(10.dp).size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Circle, contentDescription = "顯示工具列", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ViewerTopBar(
    peerName: String,
    state: ScreenMirrorState.Viewing,
    canControl: Boolean,
    /** null = 不顯示切換鈕(對方是 Android,或不能操控)。 */
    touchpadMode: Boolean?,
    onToggleTouchpad: () -> Unit,
    onKeyboard: () -> Unit,
    /** null = 對方沒有分享聲音,不顯示靜音鈕。 */
    muted: Boolean?,
    onToggleMute: () -> Unit,
    onHide: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("「$peerName」的螢幕", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (canControl) Icons.Filled.Mouse else Icons.Filled.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        (if (canControl) "可以操控" else "僅觀看") + if (state.fps > 0) "・${state.fps} fps" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            if (touchpadMode != null) {
                TextButton(onClick = onToggleTouchpad) {
                    Icon(
                        if (touchpadMode) Icons.Filled.Mouse else Icons.Filled.TouchApp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (touchpadMode) "觸控板" else "直接點按", color = Color.White)
                }
            }
            if (muted != null) {
                IconButton(onClick = onToggleMute) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (muted) "取消靜音" else "靜音",
                    )
                }
            }
            if (canControl) {
                IconButton(onClick = onKeyboard) { Icon(Icons.Filled.Keyboard, contentDescription = "鍵盤") }
            }
            TextButton(onClick = onHide) { Text("隱藏工具列", color = Color.White) }
            IconButton(onClick = onStop) { Icon(Icons.Filled.Close, contentDescription = "停止觀看") }
        }
    }
}

@Composable
private fun AndroidNavBar(onNav: (String) -> Unit, onShortcut: (List<String>) -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column {
            // 媒體鍵/音量:對方不用開無障礙服務也能用。
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                RemoteShortcuts.media.filter { it.keys.first() != RemoteKeys.STOP }.forEach { shortcut ->
                    IconButton(onClick = { onShortcut(shortcut.keys) }) { Icon(shortcut.icon, contentDescription = shortcut.label) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { onNav("back") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                IconButton(onClick = { onNav("home") }) { Icon(Icons.Filled.Circle, contentDescription = "主畫面") }
                IconButton(onClick = { onNav("recents") }) { Icon(Icons.Filled.CropSquare, contentDescription = "最近使用") }
            }
        }
    }
}

@Composable
private fun DesktopKeyBar(onKey: (String) -> Unit, onShortcut: (List<String>) -> Unit) {
    val keys = listOf("Escape" to "Esc", "Tab" to "Tab", "ArrowLeft" to "←", "ArrowUp" to "↑", "ArrowDown" to "↓", "ArrowRight" to "→",
        "Backspace" to "⌫", "Delete" to "Del", "Enter" to "Enter")
    Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CallToAction, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                keys.forEach { (key, label) ->
                    TextButton(onClick = { onKey(key) }) { Text(label, color = Color.White) }
                }
            }
            // 組合鍵(Ctrl+C、Alt+Tab、Win…)與媒體鍵:手機的鍵盤按不出來,做成按鈕。
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                (RemoteShortcuts.system + RemoteShortcuts.media).forEach { shortcut ->
                    TextButton(onClick = { onShortcut(shortcut.keys) }) {
                        Icon(shortcut.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(shortcut.label, color = Color.White)
                    }
                }
            }
        }
    }
}

/** 等比例縮放、置中後畫面在畫布上的位置。 */
private fun fitRect(canvas: IntSize, imageWidth: Int, imageHeight: Int): Rect {
    if (canvas.width <= 0 || canvas.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return Rect(0, 0, canvas.width, canvas.height)
    val scale = min(canvas.width.toFloat() / imageWidth, canvas.height.toFloat() / imageHeight)
    val w = (imageWidth * scale).toInt()
    val h = (imageHeight * scale).toInt()
    val left = (canvas.width - w) / 2
    val top = (canvas.height - h) / 2
    return Rect(left, top, left + w, top + h)
}

/** 畫布座標 → 0~1 的相對座標;落在黑邊上時夾到畫面邊緣。 */
private fun normalize(offset: Offset, fit: Rect): Pair<Double, Double>? {
    if (fit.width() <= 0 || fit.height() <= 0) return null
    val x = ((offset.x - fit.left) / fit.width()).toDouble().coerceIn(0.0, 1.0)
    val y = ((offset.y - fit.top) / fit.height()).toDouble().coerceIn(0.0, 1.0)
    return x to y
}

private typealias InputSender = (action: String, x: Double?, y: Double?, button: Int?, deltaY: Double?, key: String?, text: String?) -> Unit

private suspend fun PointerInputScope.detectSingleTaps(onTap: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation()
        if (up != null) onTap()
    }
}

/** 對方是 Android:第一根手指的按下/移動/放開原樣轉送,對方會整筆重播成手勢。 */
private suspend fun PointerInputScope.handleTouchForwarding(
    normalize: (Offset) -> Pair<Double, Double>?,
    send: InputSender,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pointerId = down.id
        var last = down.position
        normalize(last)?.let { (x, y) -> send(ScreenInputAction.POINTER_DOWN, x, y, 0, null, null, null) }
        var lastSentAt = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            change.consume()
            last = change.position
            if (!change.pressed) break
            if (change.uptimeMillis - lastSentAt >= MOVE_THROTTLE_MS) {
                lastSentAt = change.uptimeMillis
                normalize(last)?.let { (x, y) -> send(ScreenInputAction.POINTER_MOVE, x, y, 0, null, null, null) }
            }
        }
        normalize(last)?.let { (x, y) -> send(ScreenInputAction.POINTER_UP, x, y, 0, null, null, null) }
    }
}

/**
 * 對方是電腦:把觸控手勢翻譯成滑鼠操作。
 * 點一下 = 左鍵點擊;按住不動超過長按時間 = 右鍵點擊;移動超過 touch slop = 按住左鍵拖曳;
 * 第二根手指放下後 = 兩指捲動(上下滑動換算成滾輪格數)。
 */
private suspend fun PointerInputScope.handleTouchAsMouse(
    normalize: (Offset) -> Pair<Double, Double>?,
    longPressTimeoutMs: Long,
    touchSlop: Float,
    send: InputSender,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        var last = start
        var dragging = false
        var scrolling = false
        var longPressed = false
        var scrollAccumulated = 0f
        var lastScrollY = 0f
        var lastSentAt = down.uptimeMillis

        // 先把游標移到手指的位置,使用者才看得到要點哪裡。
        normalize(start)?.let { (x, y) -> send(ScreenInputAction.POINTER_MOVE, x, y, null, null, null, null) }

        while (true) {
            val remaining = longPressTimeoutMs - (SystemClock.uptimeMillis() - down.uptimeMillis)
            val event = if (!dragging && !scrolling && !longPressed) {
                withTimeoutOrNull(remaining.coerceAtLeast(1)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                // 長按:右鍵點擊,這次手勢剩下的動作都忽略。
                longPressed = true
                normalize(start)?.let { (x, y) ->
                    send(ScreenInputAction.POINTER_DOWN, x, y, 1, null, null, null)
                    send(ScreenInputAction.POINTER_UP, x, y, 1, null, null, null)
                }
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            event.changes.forEach { it.consume() }

            if (pressed.size >= 2 && !dragging && !longPressed) {
                val centroidY = pressed.map { it.position.y }.average().toFloat()
                if (!scrolling) {
                    scrolling = true
                    lastScrollY = centroidY
                } else {
                    // 手指往下滑 = 內容往下拉 = 滾輪往上(正值)。
                    scrollAccumulated += centroidY - lastScrollY
                    lastScrollY = centroidY
                    val notches = (scrollAccumulated / PIXELS_PER_WHEEL_NOTCH).toInt()
                    if (notches != 0) {
                        scrollAccumulated -= notches * PIXELS_PER_WHEEL_NOTCH
                        normalize(start)?.let { (x, y) -> send(ScreenInputAction.WHEEL, x, y, null, notches.toDouble(), null, null) }
                    }
                }
                continue
            }

            if (pressed.isEmpty()) break
            if (scrolling || longPressed) continue

            val primary = pressed.first()
            last = primary.position
            if (!dragging && (last - start).getDistance() > touchSlop) {
                dragging = true
                normalize(start)?.let { (x, y) -> send(ScreenInputAction.POINTER_DOWN, x, y, 0, null, null, null) }
            }
            if (dragging && primary.uptimeMillis - lastSentAt >= MOVE_THROTTLE_MS && abs(primary.positionChangeIgnoreConsumed().getDistance()) > 0f) {
                lastSentAt = primary.uptimeMillis
                normalize(last)?.let { (x, y) -> send(ScreenInputAction.POINTER_MOVE, x, y, null, null, null, null) }
            }
        }

        when {
            dragging -> normalize(last)?.let { (x, y) -> send(ScreenInputAction.POINTER_UP, x, y, 0, null, null, null) }
            !scrolling && !longPressed -> normalize(start)?.let { (x, y) ->
                send(ScreenInputAction.POINTER_DOWN, x, y, 0, null, null, null)
                send(ScreenInputAction.POINTER_UP, x, y, 0, null, null, null)
            }
        }
    }
}

/**
 * 對方是電腦、觸控板模式:像筆電觸控板一樣操作游標(游標位置由 [cursor]/[moveCursor] 保存,0~1 相對座標)。
 * 單指滑動 = 移動游標(滑得快時加速);點一下 = 左鍵;兩指點一下 = 右鍵;兩指上下滑 = 滾輪;
 * 按住不動超過長按時間 = 按下左鍵(震動提示),接著移動就是拖曳,放開才放開左鍵。
 */
private suspend fun PointerInputScope.handleTouchpad(
    fit: () -> Rect,
    cursor: () -> Offset,
    moveCursor: (Offset) -> Unit,
    sync: TouchpadSync,
    longPressTimeoutMs: Long,
    touchSlop: Float,
    onDragStart: () -> Unit,
    send: InputSender,
) {
    fun sendAtCursor(action: String, button: Int?) {
        val c = cursor()
        send(action, c.x.toDouble(), c.y.toDouble(), button, null, null, null)
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        sync.gestureActive = true
        try {
            var maxPointers = 1
            var moved = false
            var dragging = false
            var travelled = 0f
            var scrolling = false
            var lastScrollY = 0f
            var scrollAccumulated = 0f
            var lastSentAt = 0L

            while (true) {
                val waitingForLongPress = !moved && !dragging && maxPointers == 1
                val event = if (waitingForLongPress) {
                    val remaining = longPressTimeoutMs - (SystemClock.uptimeMillis() - down.uptimeMillis)
                    withTimeoutOrNull(remaining.coerceAtLeast(1)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }

                if (event == null) {
                    // 長按不動:按下左鍵,接下來的移動就是拖曳。
                    dragging = true
                    onDragStart()
                    sendAtCursor(ScreenInputAction.POINTER_DOWN, 0)
                    continue
                }

                val pressed = event.changes.filter { it.pressed }
                event.changes.forEach { it.consume() }
                if (pressed.isEmpty()) break
                maxPointers = maxOf(maxPointers, pressed.size)

                if (pressed.size >= 2 && !dragging) {
                    val centroidY = pressed.map { it.position.y }.average().toFloat()
                    if (!scrolling) {
                        scrolling = true
                        lastScrollY = centroidY
                        continue
                    }
                    val dy = centroidY - lastScrollY
                    lastScrollY = centroidY
                    travelled += abs(dy)
                    if (travelled > touchSlop) moved = true
                    // 手指往下滑 = 內容往下拉 = 滾輪往上(正值),跟觸控板的「自然捲動」一致。
                    scrollAccumulated += dy
                    val notches = (scrollAccumulated / PIXELS_PER_WHEEL_NOTCH).toInt()
                    if (notches != 0) {
                        scrollAccumulated -= notches * PIXELS_PER_WHEEL_NOTCH
                        val c = cursor()
                        send(ScreenInputAction.WHEEL, c.x.toDouble(), c.y.toDouble(), null, notches.toDouble(), null, null)
                    }
                    continue
                }
                // 兩指手勢放開其中一指後剩下的那一指:不要突然變成移動游標。
                if (maxPointers >= 2) continue

                val change = pressed.first()
                // 上面已經 consume() 過了,positionChange() 會回傳 0,要用 IgnoreConsumed 版本才拿得到實際移動量。
                val delta = change.positionChangeIgnoreConsumed()
                travelled += delta.getDistance()
                if (travelled > touchSlop) moved = true
                if (!moved && !dragging) continue

                val rect = fit()
                if (rect.width() <= 0 || rect.height() <= 0) continue
                val elapsed = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1)
                val speed = delta.getDistance() / elapsed // px/ms
                val gain = TOUCHPAD_BASE_GAIN * (1f + (speed - 0.25f).coerceIn(0f, 2f) * 1.5f)
                val current = cursor()
                moveCursor(
                    Offset(
                        (current.x + delta.x * gain / rect.width()).coerceIn(0f, 1f),
                        (current.y + delta.y * gain / rect.height()).coerceIn(0f, 1f),
                    ),
                )
                if (change.uptimeMillis - lastSentAt >= MOVE_THROTTLE_MS) {
                    lastSentAt = change.uptimeMillis
                    sendAtCursor(ScreenInputAction.POINTER_MOVE, null)
                }
            }

            when {
                dragging -> {
                    sendAtCursor(ScreenInputAction.POINTER_MOVE, null)
                    sendAtCursor(ScreenInputAction.POINTER_UP, 0)
                }
                moved -> sendAtCursor(ScreenInputAction.POINTER_MOVE, null) // 確保最後的位置有送到。
                // 沒移動、也沒長按到開始拖曳:點擊(單指 = 左鍵、兩指 = 右鍵)。先送一次移動,確保點在目前游標的位置。
                else -> {
                    sendAtCursor(ScreenInputAction.POINTER_MOVE, null)
                    val button = if (maxPointers >= 2) 1 else 0
                    sendAtCursor(ScreenInputAction.POINTER_DOWN, button)
                    sendAtCursor(ScreenInputAction.POINTER_UP, button)
                }
            }
        } finally {
            sync.gestureActive = false
            sync.lastGestureEndedAt = SystemClock.uptimeMillis()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
