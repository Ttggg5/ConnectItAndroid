package com.connectit.android.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.connectit.android.net.ScreenInputAction
import com.connectit.android.net.ScreenMessage
import kotlin.math.hypot

/**
 * 把觀看端送來的 screen-input 事件轉成 [ScreenControlAccessibilityService] 的操作(Android 是分享端時)。
 *
 * 觸控/滑鼠左鍵是「即時」的:按下後(移動超過一點點、或按住超過 [TAP_WINDOW_MS])就開始用
 * [GestureDescription.StrokeDescription.continueStroke] 一段一段接續送出手勢——每段送完(系統回呼
 * onCompleted)馬上把這段期間累積的移動接成下一段,手指在系統眼中一直是按著的,所以拖曳、捲動清單、
 * 長按都會跟著遠端的手指即時動作。很快就放開的點擊則整筆一次送出(最可靠的點擊方式)。
 * 滑鼠右鍵 = 返回(跟 scrcpy 等工具的慣例一樣),滾輪 = 上下滑動一小段。
 *
 * 所有狀態都只在主執行緒上存取(手勢的完成回呼也在主執行緒),呼叫端可以從任何執行緒呼叫 [handle]。
 */
class ScreenInputInjector {
    private val main = Handler(Looper.getMainLooper())

    // ---- 目前這一筆觸控 ----
    private var pressed = false
    private var downAt = 0L
    private var downPoint: PointF? = null

    /** 已經開始即時送出(第一段已送出);false 代表還在判斷是不是單純的點擊。 */
    private var streaming = false

    /** 上一段送出的筆畫(下一段要從它接續),null 代表目前沒有進行中的即時手勢。 */
    private var lastSegment: GestureDescription.StrokeDescription? = null
    private var lastPoint: PointF? = null
    private var lastDispatchAt = 0L
    private var segmentInFlight = false
    private val pendingPoints = ArrayList<PointF>()
    private var releasePending = false

    private val startStreamingRunnable = Runnable { startStreaming() }

    fun handle(message: ScreenMessage) {
        main.post { handleOnMain(message) }
    }

    /** 工作階段結束或使用者取消允許操控時呼叫,丟掉還沒送完的手勢。 */
    fun reset() {
        main.post { clearStroke() }
    }

    private fun handleOnMain(message: ScreenMessage) {
        val service = ScreenControlAccessibilityService.instance ?: return
        when (message.action) {
            ScreenInputAction.POINTER_DOWN -> {
                if (message.button == BUTTON_RIGHT) {
                    service.performNavigation("back")
                    return
                }
                if (message.button != BUTTON_LEFT) return
                val point = toPixels(service, message.x, message.y) ?: return
                clearStroke()
                pressed = true
                downAt = SystemClock.uptimeMillis()
                downPoint = point
                main.postDelayed(startStreamingRunnable, TAP_WINDOW_MS)
            }
            ScreenInputAction.POINTER_MOVE -> {
                if (!pressed) return // 沒有按著的移動(滑鼠游標滑過)在觸控螢幕上沒有意義。
                val point = toPixels(service, message.x, message.y) ?: return
                pendingPoints.add(point)
                val start = downPoint
                if (!streaming && start != null && hypot(point.x - start.x, point.y - start.y) > MOVE_SLOP_PX) {
                    startStreaming()
                } else if (streaming) {
                    pump()
                }
            }
            ScreenInputAction.POINTER_UP -> {
                if (message.button != BUTTON_LEFT || !pressed) return
                toPixels(service, message.x, message.y)?.let { pendingPoints.add(it) }
                pressed = false
                if (streaming) {
                    releasePending = true
                    pump()
                } else {
                    // 很快就放開:當成點擊(或很短的快速滑動),整筆一次送出。
                    main.removeCallbacks(startStreamingRunnable)
                    val start = downPoint ?: return
                    val points = listOf(start) + pendingPoints
                    service.dispatchStroke(
                        points.mapIndexed { i, p -> Triple(p.x, p.y, if (i == points.lastIndex) SystemClock.uptimeMillis() - downAt else 0L) },
                    )
                    clearStroke()
                }
            }
            ScreenInputAction.WHEEL -> if (!pressed && lastSegment == null) scroll(service, message)
            ScreenInputAction.TEXT -> message.text?.takeIf { it.isNotEmpty() }?.let(service::insertText)
            ScreenInputAction.KEY_DOWN -> when (message.key) {
                "Backspace" -> service.deleteBackward()
                "Enter" -> service.pressEnter()
                "Escape", "BrowserBack" -> service.performNavigation("back")
                "Home" -> Unit // 不要把鍵盤的 Home(行首)誤當成回主畫面。
            }
            ScreenInputAction.NAV -> message.key?.let(service::performNavigation)
        }
    }

    /** 送出第一段(手指按下),之後的移動由 [pump] 接續。 */
    private fun startStreaming() {
        main.removeCallbacks(startStreamingRunnable)
        if (streaming || !pressed) return
        val start = downPoint ?: return
        streaming = true
        lastPoint = start
        lastDispatchAt = downAt
        dispatchSegment(pendingPoints.toList(), willContinue = true)
        pendingPoints.clear()
    }

    /** 上一段送完、而且有新的移動(或已經放開)時,送出下一段。 */
    private fun pump() {
        if (!streaming || segmentInFlight) return
        if (pendingPoints.isEmpty() && !releasePending) return
        val release = releasePending
        dispatchSegment(pendingPoints.toList(), willContinue = !release)
        pendingPoints.clear()
        if (release) {
            // 最後一段已經送出,手指放開;等它的回呼回來就結束。
            releasePending = false
        }
    }

    private fun dispatchSegment(points: List<PointF>, willContinue: Boolean) {
        val service = ScreenControlAccessibilityService.instance ?: return clearStroke()
        val from = lastPoint ?: return clearStroke()
        val path = Path().apply {
            moveTo(from.x, from.y)
            if (points.isEmpty()) {
                // 原地按住/原地放開:路徑不能是空的(只有 moveTo 會被當成空路徑而丟例外)。
                lineTo(from.x, from.y)
            } else {
                points.forEach { lineTo(it.x, it.y) }
            }
        }
        val now = SystemClock.uptimeMillis()
        // 每段的長度跟實際經過的時間一樣,手勢的速度才會跟遠端的手指一致(甩動清單才有慣性)。
        val duration = (now - lastDispatchAt).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
        val segment = try {
            lastSegment?.continueStroke(path, 0, duration, willContinue)
                ?: GestureDescription.StrokeDescription(path, 0, duration, willContinue)
        } catch (e: RuntimeException) {
            clearStroke()
            return
        }

        val gesture = GestureDescription.Builder().addStroke(segment).build()
        segmentInFlight = true
        lastDispatchAt = now
        lastPoint = points.lastOrNull() ?: from
        lastSegment = if (willContinue) segment else null
        val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                segmentInFlight = false
                if (!willContinue) clearStroke() else pump()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // 被系統或其他觸控打斷(例如分享端的人自己摸了螢幕):這一筆就此結束。
                clearStroke()
            }
        }, main)
        if (!dispatched) clearStroke()
    }

    private fun clearStroke() {
        main.removeCallbacks(startStreamingRunnable)
        pressed = false
        streaming = false
        downPoint = null
        lastSegment = null
        lastPoint = null
        segmentInFlight = false
        releasePending = false
        pendingPoints.clear()
    }

    private fun scroll(service: ScreenControlAccessibilityService, message: ScreenMessage) {
        val delta = message.deltaY ?: return
        val start = toPixels(service, message.x, message.y) ?: return
        val (_, screenHeight) = service.realScreenSize()
        // 滾輪往上(delta > 0)= 內容往下捲回去 = 手指往下滑。每格約螢幕高度的 12%,一次最多 4 格。
        val distance = (delta.coerceIn(-4.0, 4.0) * screenHeight * 0.12).toFloat()
        val endY = (start.y + distance).coerceIn(1f, screenHeight - 1f)
        if (endY == start.y) return
        service.dispatchStroke(listOf(Triple(start.x, start.y, 0L), Triple(start.x, endY, WHEEL_SWIPE_MS)))
    }

    private fun toPixels(service: ScreenControlAccessibilityService, x: Double?, y: Double?): PointF? {
        if (x == null || y == null) return null
        val (width, height) = service.realScreenSize()
        return PointF((x.coerceIn(0.0, 1.0) * (width - 1)).toFloat(), (y.coerceIn(0.0, 1.0) * (height - 1)).toFloat())
    }

    private companion object {
        const val BUTTON_LEFT = 0
        const val BUTTON_RIGHT = 1

        /** 這段時間內放開、而且幾乎沒移動,就當成點擊整筆送出;超過就開始即時送出(長按也靠這個)。 */
        const val TAP_WINDOW_MS = 150L
        const val MOVE_SLOP_PX = 12f
        const val MIN_SEGMENT_MS = 8L
        const val MAX_SEGMENT_MS = 250L
        const val WHEEL_SWIPE_MS = 120L
    }
}
