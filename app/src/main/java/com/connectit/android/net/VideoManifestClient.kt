package com.connectit.android.net

import android.net.Uri
import com.connectit.android.model.ControlStateSnapshot
import com.connectit.android.model.VideoManifestResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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

/** 向影片伺服器要一次遠端控制的即時播放狀態(見 `GET /control/state`)。持續追蹤狀態請改用
 * [observeControlState](主機推送);這個只用在進畫面時的單次查詢,以及舊版主機的輪詢退路。
 * timeout 特意設短一點,避免單次查詢卡住太久。 */
suspend fun fetchControlState(host: String, port: Int): ControlStateSnapshot? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL("http://$host:$port/control/state").openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            ControlStateSnapshot.parse(body)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private const val CONTROL_EVENTS_CONNECT_TIMEOUT_MS = 3_000
// 主機沒有變更時每 5 秒會送一次心跳,超過這個時間都沒收到任何東西就視為連線已經死掉(例如主機
// 直接從網路上消失、沒有正常關閉連線),讓讀取丟出逾時例外、重新連線。
private const val CONTROL_EVENTS_READ_TIMEOUT_MS = 15_000
private const val CONTROL_EVENTS_RECONNECT_DELAY_MS = 1_000L
private const val LEGACY_CONTROL_POLL_INTERVAL_MS = 800L

/** 主機不支援 `/control/events`(還沒更新到有推送功能的舊版),呼叫端應退回輪詢 `/control/state`。 */
private class ControlEventsUnsupportedException : IOException()

/**
 * 訂閱主機推送的遠端控制狀態(`GET /control/events`,Server-Sent Events):連線一直開著,主機
 * 一有變更就把新的 snapshot 推過來。連線結束(正常或例外)時這個 Flow 就跟著結束,重連交給
 * [observeControlState] 處理。
 *
 * 用 callbackFlow 而不是單純的 flow { },是因為讀取是阻塞式的 socket I/O,不會回應協程取消;
 * 收集端取消時靠 awaitClose 裡的 disconnect() 直接關掉連線,讓卡在讀取的那一行立刻結束。
 */
private fun controlStateEvents(host: String, port: Int): Flow<ControlStateSnapshot> = callbackFlow {
    val connection = URL("http://$host:$port/control/events").openConnection() as HttpURLConnection
    connection.connectTimeout = CONTROL_EVENTS_CONNECT_TIMEOUT_MS
    connection.readTimeout = CONTROL_EVENTS_READ_TIMEOUT_MS
    connection.setRequestProperty("Accept", "text/event-stream")

    launch(Dispatchers.IO) {
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> throw ControlEventsUnsupportedException()
                else -> throw IOException("HTTP ${connection.responseCode}")
            }

            // SSE 格式:每個事件是一或多行 "data: ..." 加上一個空行;其他欄位(retry:、註解)忽略。
            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        ControlStateSnapshot.parse(data.toString())?.let { send(it) }
                        data.setLength(0)
                    }
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring("data:".length).removePrefix(" "))
                }
            }
            close()
        } catch (e: Exception) {
            close(e)
        }
    }

    awaitClose { connection.disconnect() }
}.buffer(Channel.CONFLATED) // 只有最新狀態有意義,收集端處理不及時直接丟掉舊的。

/**
 * 持續觀察主機的遠端控制狀態,供觀看端(清單頁、播放頁)用:優先用主機推送(`/control/events`),
 * 斷線就自動重連;主機是還沒有推送功能的舊版時,自動退回原本每 800ms 輪詢 `/control/state` 的做法。
 * 這個 Flow 不會自己結束,隨收集端的協程一起取消即可。
 */
fun observeControlState(host: String, port: Int): Flow<ControlStateSnapshot> = flow {
    var legacyPolling = false
    while (currentCoroutineContext().isActive) {
        if (legacyPolling) {
            fetchControlState(host, port)?.let { emit(it) }
            delay(LEGACY_CONTROL_POLL_INTERVAL_MS)
            continue
        }

        // catch 只攔上游(連線/讀取)的例外,不會吞掉收集端自己丟出的例外。
        emitAll(
            controlStateEvents(host, port).catch { e ->
                if (e is ControlEventsUnsupportedException) legacyPolling = true
            },
        )
        if (!legacyPolling) delay(CONTROL_EVENTS_RECONNECT_DELAY_MS)
    }
}

/** 影片伺服器的相對路徑可能包含子目錄,逐段編碼避免 '/' 被誤當成路徑分隔符以外的用途跳脫掉。 */
fun encodeRelativePathForUrl(relativePath: String): String =
    relativePath.split("/").joinToString("/") { Uri.encode(it) }

fun videoMediaUrl(host: String, port: Int, relativePath: String): String =
    "http://$host:$port/media/${encodeRelativePathForUrl(relativePath)}"

fun videoThumbnailUrl(host: String, port: Int, relativePath: String): String =
    "http://$host:$port/thumbnail/${encodeRelativePathForUrl(relativePath)}"
