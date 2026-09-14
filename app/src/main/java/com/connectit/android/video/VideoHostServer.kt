package com.connectit.android.video

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.connectit.android.net.readLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val COPY_BUFFER_SIZE = 64 * 1024

/**
 * 把這台 Android 裝置變成一個獨立的影片伺服器,對應 Windows 端的 VideoStreamingService.cs——
 * 自己實作一個最小可用的 HTTP/1.1 伺服器(只支援 GET/HEAD、Range),不依賴配對連線,任何在
 * 區網上發現這個服務的裝置(包含瀏覽器、Windows 端、其他 Android 裝置)都可以直接觀看。
 *
 * 跟 Windows 端不同的是,Android 這邊拿到的分享來源是 SAF 的 content:// Uri 而不是檔案系統路徑,
 * 所以讀檔一律透過 [Context.getContentResolver]。純邏輯類別,不是 Android Service 本身,由
 * [com.connectit.android.service.ConnectItService] 持有一個實例並把狀態轉接到 UI。
 */
class VideoHostServer(private val context: Context, private val controlState: PlaybackControlState) {

    private data class ParsedRequest(val method: String, val path: String, val headers: Map<String, String>)

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    var manifest: List<HostManifestEntry> = emptyList()
        private set
    private var manifestByRelativePath: Map<String, HostManifestEntry> = emptyMap()
    private var filesByRelativePath: Map<String, Uri> = emptyMap()
    private var serverName: String = ""

    // 縮圖產生相對昂貴,同一支影片整個伺服器存活期間只算一次,存的是 Deferred 而不是結果本身,
    // 這樣同時間好幾個請求剛好都在搶同一支還沒算完的縮圖時,大家會一起等同一個工作的結果。
    private val thumbnailCache = ConcurrentHashMap<String, Deferred<ByteArray?>>()

    // 影片長度只有遙控面板畫時間軸時才需要,一樣用 Deferred 快取,同一支影片整個伺服器
    // 存活期間只探測一次。
    private val durationCache = ConcurrentHashMap<String, Deferred<Long?>>()

    var onStatusChanged: ((String) -> Unit)? = null

    val isRunning: Boolean get() = serverSocket != null

    var port: Int = 0
        private set

    var manifestCount: Int = 0
        private set

    /** 開始分享 [treeUri](SAF 選取的資料夾)裡的所有影片。回傳是否成功啟動。 */
    suspend fun start(treeUri: Uri, name: String): Boolean {
        stop()

        val scanned = VideoLibraryScanner.buildManifest(context, treeUri)
        if (scanned.isEmpty()) {
            onStatusChanged?.invoke("找不到可分享的影片。")
            return false
        }

        val server = try {
            ServerSocket(0)
        } catch (e: IOException) {
            onStatusChanged?.invoke("開啟影片伺服器失敗:${e.message}")
            return false
        }

        manifest = scanned.map { HostManifestEntry(it.name, it.relativePath, it.size, it.modifiedEpochMillis) }
        manifestByRelativePath = manifest.associateBy { it.relativePath.lowercase() }
        filesByRelativePath = scanned.associate { it.relativePath.lowercase() to it.uri }
        serverName = name
        serverSocket = server
        port = server.localPort
        manifestCount = manifest.size

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch { acceptLoop(server) }

        onStatusChanged?.invoke("影片伺服器已啟動(連接埠 $port),共 ${manifest.size} 部影片。")
        return true
    }

    fun stop() {
        val wasRunning = serverSocket != null

        scope?.cancel()
        scope = null
        runCatching { serverSocket?.close() }
        serverSocket = null

        durationCache.clear()
        manifest = emptyList()
        manifestByRelativePath = emptyMap()
        filesByRelativePath = emptyMap()
        thumbnailCache.clear()
        port = 0
        manifestCount = 0
        controlState.reset()

        if (wasRunning) {
            onStatusChanged?.invoke("影片伺服器已關閉。")
        }
    }

    /** 供遙控面板畫時間軸用——同一個 process 內直接呼叫,不像縮圖/媒體是走 HTTP 端點。
     * 探測失敗(格式不支援/檔案有問題)回傳 null,呼叫端要自行處理「不知道總長度」的情況。 */
    suspend fun getDurationMs(relativePath: String): Long? {
        val key = relativePath.lowercase()
        val uri = filesByRelativePath[key] ?: return null
        val currentScope = scope ?: return null
        val deferred = durationCache.computeIfAbsent(key) {
            currentScope.async { VideoDurationProbe.tryGetDurationMs(context, uri) }
        }
        return deferred.await()
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        val currentScope = scope ?: return
        while (currentScope.isActive) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                return // listener 已經被 stop(),結束這個迴圈。
            }
            currentScope.launch(Dispatchers.IO) { handleClient(socket) }
        }
    }

    /** 每個連線只處理一個請求就關閉(回應帶 "Connection: close"),不做 keep-alive/pipelining,
     * 對觀看/拖曳進度來說夠用,也大幅簡化實作。 */
    private suspend fun handleClient(socket: Socket) {
        socket.use {
            try {
                socket.tcpNoDelay = true
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                val request = readRequest(input) ?: return
                routeRequest(output, request)
            } catch (e: IOException) {
            } catch (e: Exception) {
            }
        }
    }

    private fun readRequest(input: InputStream): ParsedRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        return ParsedRequest(parts[0], parts[1], headers)
    }

    private suspend fun routeRequest(output: OutputStream, request: ParsedRequest) {
        // 請求列裡的路徑可能帶查詢字串(例如 "/watch?v=2"),而且是尚未解碼的原始形式,
        // 要先拆開、解碼才能比對路由。
        val path = Uri.decode(request.path.substringBefore('?'))

        // 遠端控制模式中,觀眾不該看得到可以自己瀏覽/挑影片的首頁清單——跟原生 App 端「鎖住
        // VideoServerScreen,只能看主機正在播的那一部」是同一個道理。所以首頁、觀看頁都改成
        // 完全以主機目前選的影片為準,忽略請求本身要求的是哪一部/哪個排序。
        val controlSnapshot = controlState.snapshot()

        if (path == "/" || path == "/index.html") {
            if (controlSnapshot.enabled) {
                val activeRelativePath = controlSnapshot.videoRelativePath
                if (activeRelativePath != null) {
                    writeRedirect(output, "/watch?path=${Uri.encode(activeRelativePath)}")
                } else {
                    writeHtml(output, VideoHostPages.buildRemoteWaitingPageHtml(serverName))
                }
                return
            }

            // 只有一部影片時不用另外顯示「只有一格」的清單頁,直接跳到觀看頁。
            if (manifest.size == 1) {
                writeRedirect(output, "/watch?v=0")
                return
            }

            val sort = parseSortOption(request.path)
            writeHtml(output, VideoHostPages.buildHomePageHtml(manifest, serverName, sort))
            return
        }

        if (path.equals("/watch", ignoreCase = true)) {
            if (controlSnapshot.enabled) {
                val activeRelativePath = controlSnapshot.videoRelativePath
                if (activeRelativePath == null) {
                    writeHtml(output, VideoHostPages.buildRemoteWaitingPageHtml(serverName))
                    return
                }

                // 忽略請求本身要求的是哪一部——不管網址列打的是哪個 v=/path=,遠端控制中一律
                // 只顯示主機目前選的那一部,這樣觀眾沒有辦法透過改網址繞過鎖定。
                val activeIndex = manifestByRelativePath[activeRelativePath.lowercase()]?.let { manifest.indexOf(it) }
                if (activeIndex == null || activeIndex < 0) {
                    writeStatusOnly(output, 404, "Not Found")
                    return
                }

                val sort = parseSortOption(request.path)
                writeHtml(output, VideoHostPages.buildWatchPageHtml(manifest, serverName, activeIndex, sort, controlSnapshot))
                return
            }

            val index = resolveVideoIndex(request.path)
            if (index == null || index < 0 || index >= manifest.size) {
                writeStatusOnly(output, 404, "Not Found")
                return
            }

            val sort = parseSortOption(request.path)
            writeHtml(output, VideoHostPages.buildWatchPageHtml(manifest, serverName, index, sort, controlSnapshot))
            return
        }

        if (path.equals("/manifest", ignoreCase = true)) {
            writeManifest(output)
            return
        }

        if (path.equals("/control/state", ignoreCase = true)) {
            writeControlState(output)
            return
        }

        val mediaPrefix = "/media/"
        if (path.startsWith(mediaPrefix, ignoreCase = true)) {
            writeMedia(output, request, path.substring(mediaPrefix.length))
            return
        }

        val thumbnailPrefix = "/thumbnail/"
        if (path.startsWith(thumbnailPrefix, ignoreCase = true)) {
            writeThumbnail(output, path.substring(thumbnailPrefix.length))
            return
        }

        writeStatusOnly(output, 404, "Not Found")
    }

    private fun getQueryParam(rawPathWithQuery: String, key: String): String? {
        val queryIndex = rawPathWithQuery.indexOf('?')
        if (queryIndex < 0) return null

        for (pair in rawPathWithQuery.substring(queryIndex + 1).split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == key) {
                return Uri.decode(kv[1])
            }
        }
        return null
    }

    private fun parseVideoIndex(rawPathWithQuery: String): Int? =
        getQueryParam(rawPathWithQuery, "v")?.toIntOrNull()

    /** 除了原本以 index 選片("?v=")外,也支援以相對路徑選片("?path=")——遠端控制切換影片時
     * index 會因為排序方式不同而不穩定,相對路徑才是跨排序、跨裝置都穩定的識別方式。 */
    private fun resolveVideoIndex(rawPathWithQuery: String): Int? {
        val path = getQueryParam(rawPathWithQuery, "path")
        if (path != null) {
            val entry = manifestByRelativePath[path.lowercase()] ?: return null
            return manifest.indexOf(entry).takeIf { it >= 0 }
        }
        return parseVideoIndex(rawPathWithQuery)
    }

    private fun parseSortOption(rawPathWithQuery: String): VideoSort =
        VideoSort.fromValue(getQueryParam(rawPathWithQuery, "sort"))

    // ===================== 回應寫出 =====================

    private fun writeHtml(output: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        writeHeaders(output, 200, "OK", listOf("Content-Type" to "text/html; charset=utf-8", "Content-Length" to bytes.size.toString()))
        output.write(bytes)
    }

    private fun writeRedirect(output: OutputStream, location: String) {
        writeHeaders(output, 302, "Found", listOf("Location" to location, "Content-Length" to "0"))
    }

    private fun writeHeaders(output: OutputStream, statusCode: Int, statusText: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
        for ((name, value) in headers) {
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeStatusOnly(output: OutputStream, statusCode: Int, statusText: String) {
        writeHeaders(output, statusCode, statusText, listOf("Content-Length" to "0"))
    }

    private fun writeManifest(output: OutputStream) {
        val entries = JSONArray()
        for (entry in manifest) {
            entries.put(
                JSONObject()
                    .put("Name", entry.name)
                    .put("RelativePath", entry.relativePath)
                    .put("Size", entry.size)
                    .put("Modified", Instant.ofEpochMilli(entry.modifiedEpochMillis).toString()),
            )
        }
        val payload = JSONObject()
            .put("Name", serverName)
            .put("Entries", entries)
            .put("RemoteControlEnabled", controlState.snapshot().enabled)
            .toString().toByteArray(Charsets.UTF_8)

        writeHeaders(output, 200, "OK", listOf("Content-Type" to "application/json", "Content-Length" to payload.size.toString()))
        output.write(payload)
    }

    private fun writeControlState(output: OutputStream) {
        val payload = controlState.snapshot().toJson().toString().toByteArray(Charsets.UTF_8)
        writeHeaders(output, 200, "OK", listOf("Content-Type" to "application/json", "Content-Length" to payload.size.toString()))
        output.write(payload)
    }

    private suspend fun writeThumbnail(output: OutputStream, relativePath: String) {
        val key = relativePath.lowercase()
        val uri = filesByRelativePath[key]
        val currentScope = scope
        if (uri == null || currentScope == null) {
            writeStatusOnly(output, 404, "Not Found")
            return
        }

        val deferred = thumbnailCache.computeIfAbsent(key) {
            currentScope.async { VideoThumbnailGenerator.tryGenerate(context, uri) }
        }
        val jpeg = deferred.await()
        if (jpeg == null) {
            writeStatusOnly(output, 404, "Not Found")
            return
        }

        writeHeaders(
            output, 200, "OK",
            listOf("Content-Type" to "image/jpeg", "Content-Length" to jpeg.size.toString(), "Cache-Control" to "public, max-age=86400"),
        )
        output.write(jpeg)
    }

    private fun writeMedia(output: OutputStream, request: ParsedRequest, relativePath: String) {
        val key = relativePath.lowercase()
        val uri = filesByRelativePath[key]
        val manifestEntry = manifestByRelativePath[key]
        if (uri == null || manifestEntry == null) {
            writeStatusOnly(output, 404, "Not Found")
            return
        }

        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            writeStatusOnly(output, 404, "Not Found")
            return
        }

        // 用 AutoCloseInputStream 包住 pfd,讓串流跟底層 fd 只有單一個關閉路徑,避免各自
        // 手動 close() 導致同一個 fd 被關兩次。
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { fis ->
            val totalLength = pfd.statSize.takeIf { it >= 0 } ?: manifestEntry.size
            val (start, end, isRangeRequest) = parseRange(request.headers["range"], totalLength)
            if (start < 0) {
                writeHeaders(output, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$totalLength", "Content-Length" to "0"))
                return
            }

            val contentLength = end - start + 1
            val headers = mutableListOf(
                "Accept-Ranges" to "bytes",
                "Content-Type" to getContentType(relativePath),
                "Content-Length" to contentLength.toString(),
            )
            if (isRangeRequest) {
                headers.add("Content-Range" to "bytes $start-$end/$totalLength")
            }

            writeHeaders(output, if (isRangeRequest) 206 else 200, if (isRangeRequest) "Partial Content" else "OK", headers)

            if (request.method.equals("HEAD", ignoreCase = true)) {
                return
            }

            fis.channel.position(start)
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = fis.read(buffer, 0, toRead)
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    /** 解析 "Range: bytes=start-end" 標頭。回傳 start &lt; 0 代表範圍不合法(應回 416)。 */
    private fun parseRange(rangeHeader: String?, totalLength: Long): Triple<Long, Long, Boolean> {
        if (rangeHeader.isNullOrEmpty() || !rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            return Triple(0, totalLength - 1, false)
        }

        val spec = rangeHeader.substring("bytes=".length).split('-')
        if (spec.size != 2) {
            return Triple(0, totalLength - 1, false)
        }

        val hasStart = spec[0].isNotEmpty()
        val hasEnd = spec[1].isNotEmpty()
        var start = spec[0].toLongOrNull() ?: 0
        var end = spec[1].toLongOrNull() ?: 0

        if (!hasStart && !hasEnd) {
            return Triple(-1, -1, true)
        }

        if (!hasStart) {
            // "-500" 代表檔案最後 500 bytes。
            start = totalLength - end
            end = totalLength - 1
        } else if (!hasEnd) {
            end = totalLength - 1
        }

        if (start < 0 || end >= totalLength || start > end) {
            return Triple(-1, -1, true)
        }

        return Triple(start, end, true)
    }

    private fun getContentType(relativePath: String): String = when (relativePath.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "mpg", "mpeg" -> "video/mpeg"
        else -> "application/octet-stream"
    }
}
