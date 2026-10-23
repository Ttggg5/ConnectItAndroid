package com.connectit.android.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.connectit.android.net.SCREEN_FRAME_HEADER_SIZE

/** 觀看端顯示用的一張畫面;[seq] 每張都不同,讓 Compose 知道要重畫(點陣圖物件本身會被重複使用)。 */
data class ScreenFrame(val bitmap: Bitmap, val seq: Int)

/**
 * 把收到的 JPEG 解碼成點陣圖。電腦螢幕一張 1600×900 的點陣圖就要 5.7MB,每秒十幾張全部重新配置
 * 會讓 GC 很吃力,所以輪流重複使用 [POOL_SIZE] 張(BitmapFactory 的 inBitmap):畫面上正在顯示的
 * 那張不會馬上被下一張蓋掉,至少隔兩張才輪回來。非執行緒安全,呼叫端保證同一時間只有一個執行緒解碼。
 */
class ScreenFrameDecoder {
    private val pool = arrayOfNulls<Bitmap>(POOL_SIZE)
    private var next = 0

    /** [payload] 是畫面訊框的完整內容([序號][JPEG])。失敗回傳 null。 */
    fun decode(payload: ByteArray): ScreenFrame? {
        if (payload.size <= SCREEN_FRAME_HEADER_SIZE) return null
        val seq = readSeq(payload)
        val slot = next
        next = (next + 1) % POOL_SIZE

        val reusable = pool[slot]
        val bitmap = decodeInto(payload, reusable) ?: decodeInto(payload, null) ?: return null
        if (bitmap !== reusable) {
            pool[slot] = bitmap
        }
        return ScreenFrame(bitmap, seq)
    }

    private fun decodeInto(payload: ByteArray, target: Bitmap?): Bitmap? = try {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inBitmap = target
        }
        BitmapFactory.decodeByteArray(payload, SCREEN_FRAME_HEADER_SIZE, payload.size - SCREEN_FRAME_HEADER_SIZE, options)
    } catch (e: IllegalArgumentException) {
        // inBitmap 太小(對方的畫面尺寸變大了,例如旋轉),改配置一張新的。
        null
    }

    fun clear() {
        // 不主動 recycle:畫面上可能還在畫最後一張,交給 GC 回收即可。
        pool.fill(null)
        next = 0
    }

    companion object {
        private const val POOL_SIZE = 3

        fun readSeq(payload: ByteArray): Int =
            ((payload[0].toInt() and 0xFF) shl 24) or
                ((payload[1].toInt() and 0xFF) shl 16) or
                ((payload[2].toInt() and 0xFF) shl 8) or
                (payload[3].toInt() and 0xFF)

        fun writeSeq(buffer: ByteArray, seq: Int) {
            buffer[0] = ((seq ushr 24) and 0xFF).toByte()
            buffer[1] = ((seq ushr 16) and 0xFF).toByte()
            buffer[2] = ((seq ushr 8) and 0xFF).toByte()
            buffer[3] = (seq and 0xFF).toByte()
        }
    }
}
