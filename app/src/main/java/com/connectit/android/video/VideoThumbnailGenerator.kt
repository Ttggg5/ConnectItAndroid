package com.connectit.android.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 用 Android 內建的 [MediaMetadataRetriever] 從影片抓一張畫面當縮圖,對應 Windows 端的
 * VideoThumbnailGenerator.cs(那邊用 Windows 殼層的 IShellItemImageFactory)。不需要額外掛
 * ffmpeg 之類的外部工具/相依套件,任何 Android 內建解碼器支援的影片格式都能用。
 */
object VideoThumbnailGenerator {
    private const val THUMBNAIL_SIZE = 320
    private const val FRAME_TIME_US = 1_000_000L // 抓第 1 秒的畫面,避免部分影片開頭是全黑格。

    // 限制同時間最多幾個縮圖產生工作,避免使用者一次打開有很多影片的資料夾清單頁時,
    // 瞬間衝出大量工作同時解碼、讀取磁碟。
    private val concurrencyLimit = Semaphore(4)

    /** 嘗試產生縮圖,回傳 JPEG 位元組;格式不支援/檔案有問題等情況回傳 null。 */
    suspend fun tryGenerate(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        concurrencyLimit.withPermit {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val bitmap = extractFrame(retriever) ?: return@runCatching null
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        out.toByteArray()
                    }
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }

    private fun extractFrame(retriever: MediaMetadataRetriever): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                retriever.getScaledFrameAtTime(
                    FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_SIZE, THUMBNAIL_SIZE,
                )
            }.getOrNull()?.let { return it }
        }

        return retriever.getFrameAtTime(FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
    }
}
