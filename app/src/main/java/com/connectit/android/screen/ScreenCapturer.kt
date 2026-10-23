package com.connectit.android.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import com.connectit.android.net.MAX_FRAME_SIZE
import com.connectit.android.net.SCREEN_FRAME_HEADER_SIZE
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 用 MediaProjection 把整個螢幕鏡像到一個 VirtualDisplay → ImageReader,再壓成 JPEG。
 *
 * 採「拉」的模式而不是每張都處理:ImageReader 有新畫面時只發一個(會合併的)通知,真正取圖/壓縮
 * 是傳送端準備好送下一張時才呼叫 [encodeLatest],用 acquireLatestImage 直接拿最新那張——
 * 中間來不及送的畫面自然被跳過,靜止的畫面也不會白白一直壓縮。
 *
 * 螢幕旋轉時重建 ImageReader 並 resize VirtualDisplay,透過 [onSizeChanged] 通知新的寬高。
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val onSizeChanged: (width: Int, height: Int) -> Unit,
    private val onStoppedBySystem: () -> Unit,
) {
    private val thread = HandlerThread("ConnectIt-ScreenCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val frameAvailable = Channel<Unit>(Channel.CONFLATED)

    private val lock = Any()
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var stopped = false

    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    // 取圖/壓縮用的可重複使用緩衝,只在持有 lock 時使用。
    private var paddedBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var copyBuffer: ByteBuffer? = null
    private val jpegStream = ExposedByteArrayOutputStream(256 * 1024)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 使用者從系統的「正在投放」提示/通知按了停止,或系統把投放收回去了。
            if (!stopped) onStoppedBySystem()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) resizeIfNeeded()
        }
    }

    /** 失敗(例如投放權杖已經失效)時丟出例外。 */
    fun start() {
        // Android 14 起規定:建立 VirtualDisplay 之前一定要先註冊 callback,否則直接丟例外。
        projection.registerCallback(projectionCallback, handler)
        val (w, h, dpi) = targetSize()
        synchronized(lock) {
            val newReader = createReader(w, h)
            reader = newReader
            width = w
            height = h
            virtualDisplay = projection.createVirtualDisplay(
                "ConnectIt-ScreenMirror", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, handler,
            )
        }
        displayManager.registerDisplayListener(displayListener, handler)
    }

    /** 掛起直到有新畫面可以取(可能已經被合併成一次通知)。 */
    suspend fun awaitFrame() {
        frameAvailable.receive()
    }

    /**
     * 取出最新一張畫面壓成 JPEG。回傳的陣列前 [SCREEN_FRAME_HEADER_SIZE] bytes 預留給呼叫端填序號,
     * 第二個值是有效長度;陣列是共用緩衝,下一次呼叫前必須用完。沒有新畫面或失敗時回傳 null。
     */
    fun encodeLatest(quality: Int): Pair<ByteArray, Int>? = synchronized(lock) {
        if (stopped) return null
        val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height
            val paddedWidth = rowStride / pixelStride

            val padded = paddedBitmap?.takeIf { it.width == paddedWidth && it.height == h }
                ?: Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888).also {
                    paddedBitmap?.recycle()
                    paddedBitmap = it
                }

            // 有些裝置最後一列沒有補齊 rowStride,緩衝區會比整張點陣圖小一點,直接 copy 會丟例外。
            val buffer = plane.buffer
            val needed = padded.byteCount
            if (buffer.remaining() >= needed) {
                padded.copyPixelsFromBuffer(buffer)
            } else {
                val copy = copyBuffer?.takeIf { it.capacity() >= needed } ?: ByteBuffer.allocateDirect(needed).also { copyBuffer = it }
                copy.clear()
                copy.put(buffer)
                while (copy.position() < needed) copy.put(0.toByte())
                copy.flip()
                padded.copyPixelsFromBuffer(copy)
            }

            val source = if (paddedWidth == w) {
                padded
            } else {
                val cropped = croppedBitmap?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        croppedBitmap?.recycle()
                        croppedBitmap = it
                    }
                Canvas(cropped).drawBitmap(padded, Rect(0, 0, w, h), Rect(0, 0, w, h), null)
                cropped
            }

            compress(source, quality) ?: compress(source, FALLBACK_QUALITY)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): Pair<ByteArray, Int>? {
        jpegStream.reset()
        jpegStream.write(ByteArray(SCREEN_FRAME_HEADER_SIZE))
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegStream)) return null
        // 1 個型別 byte + 序號 + JPEG 必須塞得進單一訊框。
        if (jpegStream.size() + 1 > MAX_FRAME_SIZE) return null
        return jpegStream.buffer() to jpegStream.size()
    }

    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            runCatching { displayManager.unregisterDisplayListener(displayListener) }
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { reader?.close() }
            reader = null
            paddedBitmap?.recycle()
            paddedBitmap = null
            croppedBitmap?.recycle()
            croppedBitmap = null
        }
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        frameAvailable.close()
        thread.quitSafely()
    }

    private fun createReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ frameAvailable.trySend(Unit) }, handler)
        }

    private fun resizeIfNeeded() {
        val (w, h, dpi) = targetSize()
        synchronized(lock) {
            if (stopped || (w == width && h == height)) return
            val display = virtualDisplay ?: return
            val oldReader = reader
            val newReader = createReader(w, h)
            display.resize(w, h, dpi)
            display.surface = newReader.surface
            reader = newReader
            width = w
            height = h
            runCatching { oldReader?.close() }
        }
        onSizeChanged(w, h)
        frameAvailable.trySend(Unit)
    }

    /** 依目前螢幕方向算出擷取尺寸:長邊最多 [MAX_LONG_EDGE],寬高都是偶數。 */
    private fun targetSize(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val realW = max(1, metrics.widthPixels)
        val realH = max(1, metrics.heightPixels)
        val scale = minOf(1.0, MAX_LONG_EDGE.toDouble() / max(realW, realH))
        val w = ((realW * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        val h = ((realH * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        val dpi = (metrics.densityDpi * scale).roundToInt().coerceAtLeast(1)
        return Triple(w, h, dpi)
    }

    private class ExposedByteArrayOutputStream(size: Int) : ByteArrayOutputStream(size) {
        fun buffer(): ByteArray = buf
    }

    companion object {
        /** 長邊上限:手機 1080×2400 會縮成約 576×1280,畫質足夠看清楚文字,一張 JPEG 約 60~150KB。 */
        private const val MAX_LONG_EDGE = 1280
        const val DEFAULT_QUALITY = 70
        private const val FALLBACK_QUALITY = 40
    }
}
