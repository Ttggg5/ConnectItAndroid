package com.connectit.android.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 螢幕鏡像的「被遠端操控」能力:Android 沒有給一般 App 注入觸控事件的 API,唯一的正規管道是
 * 無障礙服務的 [dispatchGesture](模擬手指手勢)、[performGlobalAction](返回/主畫面/最近使用)
 * 與對輸入框節點的 ACTION_SET_TEXT(打字)。使用者必須自己到系統的無障礙設定裡開啟這個服務。
 *
 * 這個服務本身什麼都不會主動做——只有 [ScreenInputInjector] 在「正在分享螢幕、而且分享端使用者
 * 勾選了允許操控」的時候才會呼叫它(見 ScreenMirrorController)。不監聽任何無障礙事件內容。
 */
class ScreenControlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        if (instance === this) {
            instance = null
            _running.value = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 目前螢幕的實際像素大小(含系統列),遠端送來的 0~1 相對座標以這個為基準換算。 */
    fun realScreenSize(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /** [points] 是 (x 像素, y 像素, 相對於第一點的毫秒) 的序列,一筆畫完整送出。 */
    fun dispatchStroke(points: List<Triple<Float, Float, Long>>) {
        if (points.isEmpty()) return
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
        }
        val duration = (points.last().third - points.first().third).coerceIn(MIN_STROKE_MS, MAX_STROKE_MS)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun performNavigation(key: String) {
        when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    /** 在目前取得輸入焦點的文字框游標處插入文字;沒有可編輯的焦點時什麼都不做。 */
    fun insertText(text: String) {
        editFocusedText { current, start, end ->
            val updated = current.substring(0, start) + text + current.substring(end)
            updated to start + text.length
        }
    }

    /** 刪除游標前一個字元(有選取範圍時刪除整個選取範圍),對應實體鍵盤的 Backspace。 */
    fun deleteBackward() {
        editFocusedText { current, start, end ->
            when {
                start != end -> current.substring(0, start) + current.substring(end) to start
                start > 0 -> {
                    // 不要把 emoji 之類的代理對切成兩半。
                    val cut = if (start >= 2 && Character.isSurrogatePair(current[start - 2], current[start - 1])) 2 else 1
                    current.substring(0, start - cut) + current.substring(end) to start - cut
                }
                else -> null
            }
        }
    }

    /** 對應 Enter:Android 11+ 的輸入框支援 IME 的「送出/前往/完成」動作;更舊的版本沒有對應的無障礙動作。 */
    fun pressEnter() {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    private inline fun editFocusedText(edit: (current: String, start: Int, end: Int) -> Pair<String, Int>?) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!node.isEditable) return
        // 空的輸入框 node.text 會是提示文字(hint),不能當成既有內容接在後面。
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            start = current.length
            end = current.length
        }
        if (start > end) start = end.also { end = start }

        val (updated, cursor) = edit(current, start, end) ?: return
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) },
        )
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
    }

    companion object {
        /** dispatchGesture 的筆畫長度必須大於 0。 */
        private const val MIN_STROKE_MS = 1L

        /** 系統對單一手勢的長度有上限(GestureDescription.getMaxGestureDuration() 是 60 秒),留點餘裕。 */
        const val MAX_STROKE_MS = 10_000L

        @Volatile var instance: ScreenControlAccessibilityService? = null
            private set

        private val _running = MutableStateFlow(false)

        /** 使用者是否已在系統設定裡開啟這個無障礙服務(而且系統已經連上它)。 */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /**
         * 開啟系統設定讓使用者打開這個服務:優先直接跳到「這個服務」的詳細頁(一鍵就能開),
         * 系統不支援時退回整個無障礙設定清單。
         */
        fun openSettings(context: Context) {
            val component = ComponentName(context, ScreenControlAccessibilityService::class.java).flattenToString()
            val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(details)
            } catch (e: Exception) {
                runCatching { context.startActivity(list) }
            }
        }

        /**
         * Android 13 起,不是從商店安裝的 App(例如從 GitHub 下載的 APK)預設不能開啟無障礙服務,
         * 開關會是灰的、顯示「受限制的設定」;要先到「應用程式資訊」右上角選單按「允許受限制的設定」。
         */
        val mayBeRestricted: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun openAppInfo(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS(API 33 才公開,舊版系統多半也認得這個 action)。 */
        private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    }
}
