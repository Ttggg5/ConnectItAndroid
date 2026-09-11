package com.connectit.android.net

import android.net.Uri
import com.connectit.android.model.VideoManifestResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 向 Windows 端的影片伺服器要清單(見 VideoStreamingService.cs 的 `GET /manifest`)。 */
suspend fun fetchVideoManifest(host: String, port: Int): VideoManifestResponse? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL("http://$host:$port/manifest").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            VideoManifestResponse.parse(body)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** 影片伺服器的相對路徑可能包含子目錄,逐段編碼避免 '/' 被誤當成路徑分隔符以外的用途跳脫掉。 */
fun encodeRelativePathForUrl(relativePath: String): String =
    relativePath.split("/").joinToString("/") { Uri.encode(it) }

fun videoMediaUrl(host: String, port: Int, relativePath: String): String =
    "http://$host:$port/media/${encodeRelativePathForUrl(relativePath)}"

fun videoThumbnailUrl(host: String, port: Int, relativePath: String): String =
    "http://$host:$port/thumbnail/${encodeRelativePathForUrl(relativePath)}"
