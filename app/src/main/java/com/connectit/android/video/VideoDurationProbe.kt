package com.connectit.android.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 讀取影片檔案的總長度中繼資料,不需要真的解碼/播放——遙控面板的時間軸需要知道總長度才能畫,
 * 主機端本身平常完全不解析媒體檔案(見 PlaybackControlState.kt 的說明),這裡是唯一的例外。
 * 對應 Windows 端的 VideoDurationProbe.cs(用 WPF 內建的 MediaPlayer 讀 NaturalDuration)。
 */
object VideoDurationProbe {
    suspend fun tryGetDurationMs(context: Context, uri: Uri): Long? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
