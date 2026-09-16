package com.connectit.android.video

import android.net.Uri

/** 影片伺服器 manifest 裡的一筆項目,對應 Windows 端的 VideoManifestEntry.cs。 */
data class HostManifestEntry(
    val name: String,
    val relativePath: String,
    val size: Long,
    val modifiedEpochMillis: Long,
)

/** 排序方式:(query string 用的值, 下拉選單顯示的文字)。跟 Windows 端 VideoStreamingService.cs
 * 的 SortOptions 一一對應,兩邊算出來的排序結果才會一致。 */
enum class VideoSort(val value: String, val label: String) {
    NAME_ASC("name_asc", "檔名(A→Z)"),
    NAME_DESC("name_desc", "檔名(Z→A)"),
    SIZE_ASC("size_asc", "檔案大小(小→大)"),
    SIZE_DESC("size_desc", "檔案大小(大→小)"),
    DATE_DESC("date_desc", "修改時間(新→舊)"),
    DATE_ASC("date_asc", "修改時間(舊→新)");

    companion object {
        const val DEFAULT_VALUE = "name_asc"

        fun fromValue(value: String?): VideoSort = entries.firstOrNull { it.value == value } ?: NAME_ASC
    }
}

/**
 * 產生影片伺服器的網頁(像 YouTube 一樣的首頁清單 + 觀看頁),對應 Windows 端
 * VideoStreamingService.cs 裡同名的私有方法群——刻意讓 HTML/CSS/JS 幾乎逐字一致,這樣不管
 * 觀看端連到的是 Windows 主機還是 Android 主機分享出來的影片庫,體驗都完全一樣。
 *
 * 只依賴內嵌的 SVG 圖示/CSS/JS,不連外部 CDN——伺服器只在區網內服務,不能假設觀看端連得到網際網路。
 */
object VideoHostPages {

    private object Icons {
        const val PLAY_ARROW = "M8 5v14l11-7z"
        const val PAUSE = "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
        const val SKIP_PREVIOUS = "M6 6h2v12H6zm3.5 6l8.5 6V6z"
        const val SKIP_NEXT = "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"
        const val FAST_REWIND = "M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z"
        const val FAST_FORWARD = "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z"
        const val VOLUME_UP = "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"
        const val VOLUME_OFF = "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"
        const val FULLSCREEN = "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"
        const val FULLSCREEN_EXIT = "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"
        const val ARROW_BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
        const val PLAY_CIRCLE = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"
        const val FOLDER = "M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
        const val SETTINGS = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
    }

    private fun svg(path: String, size: Int = 20): String =
        """<svg viewBox="0 0 24 24" width="$size" height="$size" fill="currentColor" aria-hidden="true"><path d="$path"/></svg>"""

    // 讓瀏覽器的「上一頁」永遠回到首頁,而不是照瀏覽紀錄一頁一頁往回跳到上一支影片。
    private const val BACK_TO_HOME_TRAP_SCRIPT = """
        (function () {
          if (!window.history || !window.history.pushState) { return; }
          history.pushState(null, '', location.href);
          window.addEventListener('popstate', function () {
            location.href = '/';
          });
        })();
        """

    private val SHARED_CSS = """
        :root{color-scheme:dark;--accent:#8b5cf6;}
        *{box-sizing:border-box;}
        body{margin:0;font-family:-apple-system,'Segoe UI',Roboto,Arial,sans-serif;background:#0f0f0f;color:#f1f1f1;}
        header{padding:16px 20px;border-bottom:1px solid #272727;display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap;}
        header h1{margin:0;font-size:18px;}
        a.back{color:#f1f1f1;text-decoration:none;font-size:16px;font-weight:600;display:inline-flex;align-items:center;gap:6px;}
        .sort-label{font-size:12px;color:#aaa;display:flex;align-items:center;gap:8px;white-space:nowrap;}
        .sort-label select{background:#2a2a2a;color:#f1f1f1;border:1px solid #3a3a3a;border-radius:6px;font-size:12px;padding:6px 8px;}
        .sidebar .sort-label{padding:0 2px 8px;}
        main.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:16px;padding:20px;}
        .card{color:inherit;text-decoration:none;background:#181818;border-radius:12px;overflow:hidden;display:block;}
        .card:hover{background:#222;}
        .thumb{aspect-ratio:16/9;position:relative;overflow:hidden;color:#555;background:#272727;}
        .thumb-fallback{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;}
        .thumb img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;background:#272727;}
        .card .title{padding:10px 12px 2px;font-weight:600;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
        .card .meta{padding:0 12px 12px;font-size:12px;color:#aaa;}
        main.watch{max-width:1400px;margin:0 auto;padding:20px;display:grid;grid-template-columns:minmax(0,1fr) 360px;gap:24px;align-items:start;}
        main.watch .primary{min-width:0;}
        main.watch h1{font-size:18px;margin:16px 0 4px;}
        main.watch .meta{color:#aaa;font-size:13px;margin:0 0 16px;}
        @media (max-width:860px){main.watch{grid-template-columns:minmax(0,1fr);}}

        .sidebar{display:flex;flex-direction:column;gap:4px;}
        .side-item{color:#f1f1f1;text-decoration:none;padding:6px;border-radius:8px;display:flex;gap:10px;align-items:flex-start;cursor:pointer;}
        a.side-item:hover{background:#272727;}
        .side-item.current{background:#211a2e;}
        .side-thumb{position:relative;flex:0 0 130px;width:130px;aspect-ratio:16/9;border-radius:8px;overflow:hidden;background:#272727;color:#555;}
        .side-thumb-fallback{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;}
        .side-thumb img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;}
        .side-info{min-width:0;flex:1;padding-top:1px;}
        .side-title{font-size:13px;font-weight:600;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        .side-item.current .side-title{color:var(--accent);}
        .side-meta{font-size:11px;color:#aaa;margin-top:4px;}
        .now-playing{font-size:11px;color:var(--accent);font-weight:600;margin-top:4px;display:flex;align-items:center;gap:4px;}
        .now-playing svg{width:12px;height:12px;}

        .player-wrap{position:relative;background:#000;border-radius:12px;overflow:hidden;}
        .player-wrap video{width:100%;max-height:70vh;display:block;background:#000;}
        .player-fullscreen-wrap:fullscreen,.player-fullscreen-wrap:-webkit-full-screen{width:100%;height:100%;background:#000;}
        .player-fullscreen-wrap:fullscreen .player-wrap,.player-fullscreen-wrap:-webkit-full-screen .player-wrap{width:100%;height:100%;display:flex;align-items:center;justify-content:center;border-radius:0;}
        .player-fullscreen-wrap:fullscreen .player-wrap video,.player-fullscreen-wrap:-webkit-full-screen .player-wrap video{width:100%;height:100%;max-height:none;object-fit:contain;}
        .controls{position:absolute;left:0;right:0;bottom:0;background:linear-gradient(to top, rgba(0,0,0,.85), rgba(0,0,0,.55) 65%, transparent);padding:32px 12px 10px;opacity:0;pointer-events:none;transition:opacity .15s ease;}
        .player-wrap.show-controls .controls{opacity:1;pointer-events:auto;}
        .controls input[type=range]{-webkit-appearance:none;appearance:none;width:100%;height:5px;border-radius:3px;background:#3a3a3a;outline:none;cursor:pointer;}
        .controls input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;appearance:none;width:13px;height:13px;border-radius:50%;background:var(--accent);cursor:pointer;}
        .controls input[type=range]::-moz-range-thumb{width:13px;height:13px;border:none;border-radius:50%;background:var(--accent);cursor:pointer;}
        .progress-row{padding:6px 0 4px;}
        .controls-row{display:flex;align-items:center;gap:2px;flex-wrap:wrap;}
        .controls-row button{background:none;border:none;color:#f1f1f1;cursor:pointer;padding:6px 8px;border-radius:6px;display:inline-flex;align-items:center;justify-content:center;}
        .controls-row button svg{display:block;}
        .controls-row button:hover{background:#2a2a2a;}
        .controls-row button:disabled{opacity:.3;cursor:default;}
        .controls-row button:disabled:hover{background:none;}
        .time{font-size:12px;color:#ccc;white-space:nowrap;padding:0 6px;}
        .spacer{flex:1;}
        .settings-wrap{position:relative;}
        .settings-panel{position:absolute;right:0;bottom:calc(100% + 8px);background:#282828;border-radius:10px;padding:6px 0;min-width:210px;box-shadow:0 4px 16px rgba(0,0,0,.5);display:flex;flex-direction:column;z-index:1;}
        .settings-panel[hidden]{display:none;}
        .settings-row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 14px;font-size:13px;color:#f1f1f1;white-space:nowrap;cursor:pointer;}
        .settings-row:hover{background:#333;}
        .settings-row select{background:#3a3a3a;color:#f1f1f1;border:1px solid #4a4a4a;border-radius:6px;font-size:12px;padding:4px 6px;}
        .settings-row.volume-row{cursor:default;}
        .settings-row.volume-row:hover{background:none;}
        .settings-row.volume-row button{background:none;border:none;color:#f1f1f1;cursor:pointer;padding:4px;display:inline-flex;align-items:center;justify-content:center;}
        .settings-row.volume-row input[type=range]{flex:1;min-width:0;width:auto;}
        .next-overlay{position:absolute;right:16px;bottom:96px;background:rgba(24,24,24,.95);border-radius:10px;padding:12px 14px;display:flex;align-items:center;gap:12px;box-shadow:0 4px 16px rgba(0,0,0,.5);}
        .next-overlay[hidden]{display:none;}
        .next-overlay span{font-size:13px;}
        .next-overlay button{background:var(--accent);color:#fff;border:none;border-radius:6px;padding:6px 10px;font-size:12px;cursor:pointer;}

        .player-wrap.remote-controlled .controls{display:none;}

        /* 遠端控制中改成劇院模式(不一定拿得到瀏覽器原生全螢幕權限,因為是輪詢回應後才觸發、
           不是使用者直接點擊觸發,不算「使用者手勢」——所以額外用純 CSS 讓播放區塊佔滿整個
           版面當作保底,真正的全螢幕 API 有成功的話畫面也不會衝突。) */
        body.remote-fullscreen header{display:none;}
        body.remote-fullscreen main.watch{grid-template-columns:minmax(0,1fr);max-width:none;padding:0;gap:0;}
        body.remote-fullscreen main.watch .sidebar{display:none;}
        body.remote-fullscreen main.watch h1,body.remote-fullscreen main.watch .primary>.meta{display:none;}
        body.remote-fullscreen .player-fullscreen-wrap{height:100vh;}
        body.remote-fullscreen .player-wrap{border-radius:0;height:100%;display:flex;align-items:center;justify-content:center;}
        body.remote-fullscreen .player-wrap video{max-height:100vh;height:100vh;}

        .waiting-page{display:flex;align-items:center;justify-content:center;height:100vh;text-align:center;padding:24px;}
        .waiting-page p{font-size:16px;color:#ccc;}

        .flatten-toggle{color:#ccc;text-decoration:none;font-size:12px;white-space:nowrap;border:1px solid #3a3a3a;border-radius:6px;padding:6px 10px;}
        .flatten-toggle:hover{background:#2a2a2a;}
        .breadcrumb{font-size:13px;color:#aaa;display:flex;gap:6px;flex-wrap:wrap;padding:12px 20px 0;}
        .breadcrumb a{color:#ccc;text-decoration:none;}
        .breadcrumb a:hover{text-decoration:underline;}
        .folder-thumb{color:#f5c451;display:flex;align-items:center;justify-content:center;}
        .empty-folder{padding:40px 20px;color:#888;text-align:center;}
        """.trimIndent()

    /** 遠端控制模式已開啟、但主機還沒選任何影片時顯示——不能讓觀眾自己從首頁清單挑,只能等主機
     * 選好,這裡定期輪詢 `/control/state`,一有影片就自動跳轉過去。 */
    fun buildRemoteWaitingPageHtml(serverName: String): String {
        val title = htmlEncode(serverName)
        return """
            <!doctype html>
            <html lang="zh-Hant">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            <style>$SHARED_CSS</style></head>
            <body>
              <main class="waiting-page"><p>遙控模式已開啟,等待主機選擇影片…</p></main>
              <script>
                setInterval(function () {
                  fetch('/control/state').then(function (r) { return r.json(); }).then(function (state) {
                    if (!state || !state.Enabled) { location.href = '/'; return; }
                    if (state.VideoRelativePath) { location.href = '/watch?path=' + encodeURIComponent(state.VideoRelativePath); }
                  }).catch(function () {});
                }, 800);
              </script>
            </body>
            </html>
            """.trimIndent()
    }

    private fun buildSortSelectHtml(selected: String, onChangeUrlPrefix: String, onChangeUrlSuffix: String = ""): String {
        val options = VideoSort.entries.joinToString("\n") { option ->
            val selectedAttr = if (option.value == selected) " selected" else ""
            """<option value="${option.value}"$selectedAttr>${option.label}</option>"""
        }
        return """
            <label class="sort-label">排序方式
              <select onchange="location.href='$onChangeUrlPrefix'+this.value+'$onChangeUrlSuffix'">
                $options
              </select>
            </label>
            """.trimIndent()
    }

    /** 依排序方式,回傳 manifest 項目「顯示順序」的索引清單(內容還是指向原始索引,只是走訪順序不同)。
     * 首頁清單、觀看頁側邊清單、上一部/下一部都共用同一份排序邏輯。 */
    fun displayOrder(manifest: List<HostManifestEntry>, sort: VideoSort): List<Int> {
        val indices = manifest.indices.toList()
        return when (sort) {
            VideoSort.NAME_ASC -> indices.sortedBy { manifest[it].name.lowercase() }
            VideoSort.NAME_DESC -> indices.sortedByDescending { manifest[it].name.lowercase() }
            VideoSort.SIZE_ASC -> indices.sortedBy { manifest[it].size }
            VideoSort.SIZE_DESC -> indices.sortedByDescending { manifest[it].size }
            VideoSort.DATE_ASC -> indices.sortedBy { manifest[it].modifiedEpochMillis }
            VideoSort.DATE_DESC -> indices.sortedByDescending { manifest[it].modifiedEpochMillis }
        }
    }

    fun htmlEncode(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** 影片伺服器的相對路徑可能包含子目錄,逐段編碼避免 '/' 被誤當成路徑分隔符以外的用途跳脫掉。 */
    fun escapeRelativePathForUrl(relativePath: String): String =
        relativePath.split("/").joinToString("/") { Uri.encode(it) }

    fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
    }

    /** folder/flat 兩個查詢參數共同決定首頁怎麼列影片:預設(flat=false)照實際資料夾結構逐層瀏覽,
     * folder 是目前瀏覽到的相對路徑(空字串代表根目錄);flat=true 則無視資料夾,把整個 manifest
     * 攤平成單一清單(等同這個功能加入前的行為),供使用者在網頁上自行切換。 */
    fun buildHomePageHtml(manifest: List<HostManifestEntry>, serverName: String, sort: VideoSort, folder: String, flat: Boolean): String {
        val extraQuery = buildExtraQuery(folder, flat)
        val cardsHtml: String

        if (flat) {
            cardsHtml = buildVideoCardsHtml(manifest, displayOrder(manifest, sort), sort, extraQuery)
        } else {
            val (subfolders, videoIndices) = getFolderContents(manifest, folder)
            val videoIndexSet = videoIndices.toHashSet()
            val orderedVideoIndices = displayOrder(manifest, sort).filter { videoIndexSet.contains(it) }

            val folderCards = subfolders.joinToString("\n") { name ->
                val childPath = if (folder.isEmpty()) name else "$folder/$name"
                val count = countVideosUnder(manifest, childPath)
                """
                <a class="card folder-card" href="/?folder=${Uri.encode(childPath)}&sort=${sort.value}">
                  <div class="thumb folder-thumb">${svg(Icons.FOLDER, 40)}</div>
                  <div class="title">${htmlEncode(name)}</div>
                  <div class="meta">$count 部影片</div>
                </a>
                """.trimIndent()
            }

            cardsHtml = if (subfolders.isEmpty() && videoIndices.isEmpty()) {
                """<p class="empty-folder">這個資料夾是空的。</p>"""
            } else {
                folderCards + "\n" + buildVideoCardsHtml(manifest, orderedVideoIndices, sort, extraQuery)
            }
        }

        val title = htmlEncode(serverName)
        val breadcrumb = if (flat) "" else buildBreadcrumbHtml(folder, sort)
        val flattenToggle = if (flat) {
            """<a class="flatten-toggle" href="/?sort=${sort.value}">依資料夾顯示</a>"""
        } else {
            """<a class="flatten-toggle" href="/?flat=1&sort=${sort.value}">顯示成單一清單</a>"""
        }

        return """
            <!doctype html>
            <html lang="zh-Hant">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            <style>$SHARED_CSS</style></head>
            <body>
              <header>
                <h1>$title</h1>
                ${buildSortSelectHtml(sort.value, buildHomeSortPrefix(folder, flat))}
                $flattenToggle
              </header>
              $breadcrumb
              <main class="grid">
                $cardsHtml
              </main>
              <script>$BACK_TO_HOME_TRAP_SCRIPT</script>
            </body>
            </html>
            """.trimIndent()
    }

    private fun buildVideoCardsHtml(manifest: List<HostManifestEntry>, indices: List<Int>, sort: VideoSort, extraQuery: String): String =
        indices.joinToString("\n") { index ->
            val entry = manifest[index]
            """
            <a class="card" href="/watch?v=$index&sort=${sort.value}$extraQuery">
              <div class="thumb">
                <div class="thumb-fallback">${svg(Icons.PLAY_CIRCLE, 40)}</div>
                <img src="/thumbnail/${escapeRelativePathForUrl(entry.relativePath)}" alt="" loading="lazy" onerror="this.style.display='none'">
              </div>
              <div class="title">${htmlEncode(entry.name)}</div>
              <div class="meta">${formatFileSize(entry.size)}</div>
            </a>
            """.trimIndent()
        }

    /** 依相對路徑字首比對 manifest,回傳 [folder] 底下「直屬」的子資料夾名稱(不含更深層的孫層)
     * 以及直接放在這一層的影片索引——首頁逐層瀏覽的核心邏輯。只跟 manifest 的字串比對,不會真的
     * 去讀檔案系統,folder 也不需要額外做路徑穿越檢查。 */
    private fun getFolderContents(manifest: List<HostManifestEntry>, folder: String): Pair<List<String>, List<Int>> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val subfolders = mutableListOf<String>()
        val videoIndices = mutableListOf<Int>()

        for (i in manifest.indices) {
            val relativePath = manifest[i].relativePath
            if (prefix.isNotEmpty() && !relativePath.startsWith(prefix, ignoreCase = true)) {
                continue
            }

            val remainder = relativePath.substring(prefix.length)
            val slashIndex = remainder.indexOf('/')
            if (slashIndex < 0) {
                videoIndices.add(i)
            } else {
                val name = remainder.substring(0, slashIndex)
                if (subfolders.none { it.equals(name, ignoreCase = true) }) {
                    subfolders.add(name)
                }
            }
        }

        subfolders.sortWith(String.CASE_INSENSITIVE_ORDER)
        return subfolders to videoIndices
    }

    private fun countVideosUnder(manifest: List<HostManifestEntry>, folderPath: String): Int {
        val prefix = "$folderPath/"
        return manifest.count { it.relativePath.startsWith(prefix, ignoreCase = true) }
    }

    fun normalizeFolder(folder: String?): String = folder?.trim('/') ?: ""

    /** 接在 "&sort={sort}" 後面的額外查詢字串——flat=true 帶 "&flat=1",folder 模式底下非根目錄
     * 則帶 "&folder=...",讓 watch 頁的返回/上一部/下一部/側欄連結都能保留目前瀏覽的情境。 */
    private fun buildExtraQuery(folder: String, flat: Boolean): String =
        if (flat) "&flat=1" else if (folder.isEmpty()) "" else "&folder=${Uri.encode(folder)}"

    private fun buildHomeSortPrefix(folder: String, flat: Boolean): String {
        if (flat) return "/?flat=1&sort="
        return if (folder.isEmpty()) "/?sort=" else "/?folder=${Uri.encode(folder)}&sort="
    }

    private fun buildHomeHref(sort: VideoSort, folder: String, flat: Boolean): String = buildHomeSortPrefix(folder, flat) + sort.value

    private fun buildBreadcrumbHtml(folder: String, sort: VideoSort): String {
        if (folder.isEmpty()) return ""

        val segments = folder.split('/')
        val parts = mutableListOf("""<a href="/?sort=${sort.value}">首頁</a>""")
        var accumulated = ""
        for ((i, segment) in segments.withIndex()) {
            accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
            parts.add(
                if (i == segments.lastIndex) {
                    htmlEncode(segment)
                } else {
                    """<a href="/?folder=${Uri.encode(accumulated)}&sort=${sort.value}">${htmlEncode(segment)}</a>"""
                },
            )
        }

        return """<nav class="breadcrumb">${parts.joinToString(" / ")}</nav>"""
    }

    private val SpeedOptions = listOf(0.5 to "0.5x", 1.0 to "1x", 1.25 to "1.25x", 1.5 to "1.5x", 2.0 to "2x")

    /** "0.5"/"1"/"1.25" 這種格式跟前端 JS 用同一個字串當 &lt;option value&gt; 比對,不能用會
     * 印出多餘 ".0" 的預設 Double.toString()。 */
    private fun formatSpeedValue(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun buildSpeedOptionsHtml(selected: Double): String = SpeedOptions.joinToString("\n") { (value, label) ->
        val selectedAttr = if (kotlin.math.abs(value - selected) < 0.0001) " selected" else ""
        """<option value="${formatSpeedValue(value)}"$selectedAttr>$label</option>"""
    }

    fun buildWatchPageHtml(
        manifest: List<HostManifestEntry>,
        serverName: String,
        index: Int,
        sort: VideoSort,
        controlSnapshot: PlaybackControlState.Snapshot,
        options: VideoServerPlaybackOptions = VideoServerPlaybackOptions(),
        folder: String = "",
        flat: Boolean = false,
    ): String {
        val entry = manifest[index]

        // 觀看頁的上一部/下一部、右側清單預設只在「目前這個資料夾」裡走(跟首頁逐層瀏覽一致),
        // 只有攤平模式才會照全域排序橫跨所有資料夾——不然使用者會在不知情的狀況下被帶去別的
        // 資料夾。folder 模式下用的還是全域排序,只是先篩選成這個資料夾直屬的影片而已。
        val order = if (flat) {
            displayOrder(manifest, sort)
        } else {
            val folderVideoIndices = getFolderContents(manifest, folder).second.toHashSet()
            displayOrder(manifest, sort).filter { folderVideoIndices.contains(it) }
        }
        val position = order.indexOf(index)
        val hasPrev = position > 0
        val hasNext = position in 0 until order.size - 1
        val prevIndex = if (hasPrev) order[position - 1] else null
        val nextIndex = if (hasNext) order[position + 1] else null
        val extraQuery = buildExtraQuery(folder, flat)

        val sidebar = if (order.size > 1) {
            """
            <aside class="sidebar">
              ${buildSortSelectHtml(sort.value, "/watch?v=$index&sort=", extraQuery)}
              ${buildSidebarItems(manifest, order, index, sort, extraQuery)}
            </aside>
            """.trimIndent()
        } else {
            ""
        }
        val title = htmlEncode(entry.name)
        val backLabel = htmlEncode(serverName)
        val homeHref = buildHomeHref(sort, folder, flat)

        // 傳給前端 JS 用的中繼資料,以 JSON 安全編碼字串內容,並把 "</" 斷開避免檔名剛好含有
        // "</script>" 這種字串時提早把 <script> 區塊截斷。
        val relativePathJson = org.json.JSONObject.quote(entry.relativePath).replace("</", "<\\/")
        val sortJson = org.json.JSONObject.quote(sort.value).replace("</", "<\\/")
        val extraQueryJson = org.json.JSONObject.quote(extraQuery).replace("</", "<\\/")
        val prevIndexJson = prevIndex?.toString() ?: "null"
        val nextIndexJson = nextIndex?.toString() ?: "null"
        val controlStateJson = controlSnapshot.toJson().toString().replace("</", "<\\/")
        val defaultVolumeJson = options.defaultVolumePercent.toString()
        val defaultSpeedJson = formatSpeedValue(options.defaultSpeed)
        val autoplayCountdownJson = options.autoplayCountdownSeconds.toString()

        return """
            <!doctype html>
            <html lang="zh-Hant">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            <style>$SHARED_CSS</style></head>
            <body>
              <header><a class="back" href="$homeHref">${svg(Icons.ARROW_BACK, 18)} $backLabel</a></header>
              <main class="watch">
                <div class="primary">
                  <div class="player-fullscreen-wrap" id="playerFullscreenWrap">
                    <div class="player-wrap" id="playerWrap">
                      <video id="player" src="/media/${escapeRelativePathForUrl(entry.relativePath)}" poster="/thumbnail/${escapeRelativePathForUrl(entry.relativePath)}"></video>
                      <div class="next-overlay" id="nextOverlay" hidden>
                        <span id="nextOverlayText"></span>
                        <button id="cancelNextBtn" type="button">取消</button>
                      </div>
                      <div class="controls">
                        <div class="progress-row">
                          <input type="range" id="seek" min="0" max="0" step="0.1" value="0">
                        </div>
                        <div class="controls-row">
                          <button id="prevBtn" type="button" title="上一部"${if (hasPrev) "" else " disabled"}>${svg(Icons.SKIP_PREVIOUS)}</button>
                          <button id="backBtn" type="button" title="倒退 10 秒">${svg(Icons.FAST_REWIND)}</button>
                          <button id="playBtn" type="button" title="播放/暫停">${svg(Icons.PLAY_ARROW)}</button>
                          <button id="fwdBtn" type="button" title="快轉 10 秒">${svg(Icons.FAST_FORWARD)}</button>
                          <button id="nextBtn" type="button" title="下一部"${if (hasNext) "" else " disabled"}>${svg(Icons.SKIP_NEXT)}</button>
                          <span class="time" id="time">0:00 / 0:00</span>
                          <span class="spacer"></span>
                          <div class="settings-wrap" id="settingsWrap">
                            <button id="settingsBtn" type="button" title="設定">${svg(Icons.SETTINGS)}</button>
                            <div class="settings-panel" id="settingsPanel" hidden>
                              <div class="settings-row volume-row">
                                <button id="muteBtn" type="button" title="靜音">${svg(Icons.VOLUME_UP)}</button>
                                <input type="range" id="volume" min="0" max="100" value="100">
                              </div>
                              <label class="settings-row">自動播放下一部 <input type="checkbox" id="autoplayNext"${if (options.autoplayNext) " checked" else ""}></label>
                              <label class="settings-row">播放速度
                                <select id="speed" title="播放速度">
                                  ${buildSpeedOptionsHtml(options.defaultSpeed)}
                                </select>
                              </label>
                            </div>
                          </div>
                          <button id="fsBtn" type="button" title="全螢幕">${svg(Icons.FULLSCREEN)}</button>
                        </div>
                      </div>
                    </div>
                  </div>
                  <h1>$title</h1>
                  <p class="meta">${formatFileSize(entry.size)}</p>
                </div>
                $sidebar
              </main>
              <script>$BACK_TO_HOME_TRAP_SCRIPT</script>
              <script>${buildWatchPageScript(
                  relativePathJson, prevIndexJson, nextIndexJson, sortJson, extraQueryJson, controlStateJson,
                  defaultVolumeJson, defaultSpeedJson, autoplayCountdownJson,
              )}</script>
            </body>
            </html>
            """.trimIndent()
    }

    /** 右側影片清單:依目前選的排序方式列出全部影片(含目前播放中的那一部),每筆都附縮圖,
     * 目前播放中的那筆用樣式標示、不能再點。 */
    private fun buildSidebarItems(manifest: List<HostManifestEntry>, order: List<Int>, currentIndex: Int, sort: VideoSort, extraQuery: String): String =
        order.joinToString("\n") { i ->
            val entry = manifest[i]
            val isCurrent = i == currentIndex
            val thumb = """
                <div class="side-thumb">
                  <div class="side-thumb-fallback">${svg(Icons.PLAY_CIRCLE, 24)}</div>
                  <img src="/thumbnail/${escapeRelativePathForUrl(entry.relativePath)}" alt="" loading="lazy" onerror="this.style.display='none'">
                </div>
                """.trimIndent()
            val info = """
                <div class="side-info">
                  <div class="side-title">${htmlEncode(entry.name)}</div>
                  <div class="side-meta">${formatFileSize(entry.size)}</div>
                  ${if (isCurrent) """<div class="now-playing">${svg(Icons.PLAY_ARROW)} 正在播放</div>""" else ""}
                </div>
                """.trimIndent()

            if (isCurrent) {
                """<div class="side-item current">$thumb$info</div>"""
            } else {
                """<a class="side-item" href="/watch?v=$i&sort=${sort.value}$extraQuery">$thumb$info</a>"""
            }
        }

    private fun buildWatchPageScript(
        relativePathJson: String,
        prevIndexJson: String,
        nextIndexJson: String,
        sortJson: String,
        extraQueryJson: String,
        controlStateJson: String,
        defaultVolumeJson: String,
        defaultSpeedJson: String,
        autoplayCountdownJson: String,
    ): String = """
        (function () {
          var meta = { relativePath: $relativePathJson, prevIndex: $prevIndexJson, nextIndex: $nextIndexJson, sort: $sortJson, extraQuery: $extraQueryJson };
          var DEFAULT_VOLUME_PERCENT = $defaultVolumeJson;
          var DEFAULT_SPEED = $defaultSpeedJson;
          var AUTOPLAY_COUNTDOWN_SECONDS = $autoplayCountdownJson;
          function watchUrl(index) { return '/watch?v=' + index + '&sort=' + meta.sort + meta.extraQuery; }
          function watchUrlForPath(path) { return '/watch?path=' + encodeURIComponent(path) + '&sort=' + meta.sort + meta.extraQuery; }
          var ICON_PLAY = '${svg(Icons.PLAY_ARROW)}';
          var ICON_PAUSE = '${svg(Icons.PAUSE)}';
          var ICON_VOLUME_UP = '${svg(Icons.VOLUME_UP)}';
          var ICON_VOLUME_OFF = '${svg(Icons.VOLUME_OFF)}';
          var ICON_FULLSCREEN = '${svg(Icons.FULLSCREEN)}';
          var ICON_FULLSCREEN_EXIT = '${svg(Icons.FULLSCREEN_EXIT)}';
          var video = document.getElementById('player');
          var playBtn = document.getElementById('playBtn');
          var seek = document.getElementById('seek');
          var timeLabel = document.getElementById('time');
          var backBtn = document.getElementById('backBtn');
          var fwdBtn = document.getElementById('fwdBtn');
          var prevBtn = document.getElementById('prevBtn');
          var nextBtn = document.getElementById('nextBtn');
          var muteBtn = document.getElementById('muteBtn');
          var volume = document.getElementById('volume');
          var speed = document.getElementById('speed');
          var fsBtn = document.getElementById('fsBtn');
          var autoplayNext = document.getElementById('autoplayNext');
          var nextOverlay = document.getElementById('nextOverlay');
          var nextOverlayText = document.getElementById('nextOverlayText');
          var cancelNextBtn = document.getElementById('cancelNextBtn');
          var fullscreenWrap = document.getElementById('playerFullscreenWrap');
          var playerWrap = document.getElementById('playerWrap');
          var controls = playerWrap.querySelector('.controls');
          var settingsWrap = document.getElementById('settingsWrap');
          var settingsBtn = document.getElementById('settingsBtn');
          var settingsPanel = document.getElementById('settingsPanel');

          var posKey = 'connectit-pos:' + meta.relativePath;
          var volKey = 'connectit-volume';
          var speedKey = 'connectit-speed';
          var seeking = false;
          var autoplayTimer = null;
          var hideControlsTimer = null;

          function showControls() {
            playerWrap.classList.add('show-controls');
            clearTimeout(hideControlsTimer);
            if (!video.paused) {
              hideControlsTimer = setTimeout(function () {
                playerWrap.classList.remove('show-controls');
              }, 2500);
            }
          }
          video.addEventListener('pause', function () { clearTimeout(hideControlsTimer); playerWrap.classList.add('show-controls'); });
          video.addEventListener('play', showControls);
          playerWrap.addEventListener('mousemove', showControls);
          playerWrap.addEventListener('click', showControls);
          controls.addEventListener('input', showControls);
          showControls();

          function fmt(sec) {
            if (!isFinite(sec) || sec < 0) sec = 0;
            sec = Math.floor(sec);
            var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
            var mm = (h > 0 && m < 10) ? ('0' + m) : String(m);
            var ss = s < 10 ? ('0' + s) : String(s);
            return h > 0 ? (h + ':' + mm + ':' + ss) : (mm + ':' + ss);
          }

          function updateSeekFill() {
            var dur = video.duration || 0;
            var playedPct = dur ? (video.currentTime / dur * 100) : 0;
            var bufferedPct = 0;
            if (video.buffered.length) {
              bufferedPct = dur ? (video.buffered.end(video.buffered.length - 1) / dur * 100) : 0;
            }
            seek.style.background = 'linear-gradient(to right, var(--accent) ' + playedPct + '%, #666 ' + playedPct + '%, #666 ' + bufferedPct + '%, #3a3a3a ' + bufferedPct + '%)';
          }

          function updateMuteIcon() {
            muteBtn.innerHTML = (video.muted || video.volume === 0) ? ICON_VOLUME_OFF : ICON_VOLUME_UP;
          }

          function togglePlay() {
            if (video.paused) { video.play().catch(function () {}); } else { video.pause(); }
          }

          var savedVol = localStorage.getItem(volKey);
          video.volume = Math.min(1, Math.max(0, (savedVol !== null ? parseFloat(savedVol) : DEFAULT_VOLUME_PERCENT / 100)));
          volume.value = String(Math.round(video.volume * 100));

          var savedSpeed = localStorage.getItem(speedKey);
          video.playbackRate = savedSpeed !== null ? parseFloat(savedSpeed) : DEFAULT_SPEED;
          speed.value = String(video.playbackRate);
          updateMuteIcon();

          video.addEventListener('loadedmetadata', function () {
            seek.max = String(video.duration || 0);
            var savedPos = parseFloat(localStorage.getItem(posKey) || '0');
            if (savedPos > 5 && savedPos < video.duration - 5) {
              video.currentTime = savedPos;
            }
            timeLabel.textContent = fmt(video.currentTime) + ' / ' + fmt(video.duration);
            video.play().catch(function () {});
          });

          video.addEventListener('timeupdate', function () {
            if (!seeking) { seek.value = String(video.currentTime); }
            timeLabel.textContent = fmt(video.currentTime) + ' / ' + fmt(video.duration);
            updateSeekFill();
            if (video.currentTime > 2) {
              localStorage.setItem(posKey, String(video.currentTime));
            }
          });
          video.addEventListener('progress', updateSeekFill);
          video.addEventListener('play', function () { playBtn.innerHTML = ICON_PAUSE; });
          video.addEventListener('pause', function () { playBtn.innerHTML = ICON_PLAY; });

          video.addEventListener('ended', function () {
            localStorage.removeItem(posKey);
            if (meta.nextIndex === null || !autoplayNext.checked) { return; }

            var secondsLeft = AUTOPLAY_COUNTDOWN_SECONDS;
            nextOverlay.hidden = false;
            nextOverlayText.textContent = '即將播放下一部…(' + secondsLeft + ')';
            autoplayTimer = setInterval(function () {
              secondsLeft--;
              if (secondsLeft <= 0) {
                clearInterval(autoplayTimer);
                location.href = watchUrl(meta.nextIndex);
              } else {
                nextOverlayText.textContent = '即將播放下一部…(' + secondsLeft + ')';
              }
            }, 1000);
          });
          cancelNextBtn.addEventListener('click', function () {
            clearInterval(autoplayTimer);
            nextOverlay.hidden = true;
          });

          playBtn.addEventListener('click', togglePlay);
          backBtn.addEventListener('click', function () { video.currentTime = Math.max(0, video.currentTime - 10); });
          fwdBtn.addEventListener('click', function () { video.currentTime = Math.min(video.duration || 1e9, video.currentTime + 10); });

          seek.addEventListener('input', function () {
            seeking = true;
            timeLabel.textContent = fmt(parseFloat(seek.value)) + ' / ' + fmt(video.duration);
          });
          seek.addEventListener('change', function () {
            video.currentTime = parseFloat(seek.value);
            seeking = false;
          });

          volume.addEventListener('input', function () {
            video.volume = Number(volume.value) / 100;
            video.muted = false;
            localStorage.setItem(volKey, String(video.volume));
            updateMuteIcon();
          });
          muteBtn.addEventListener('click', function () { video.muted = !video.muted; updateMuteIcon(); });

          speed.addEventListener('change', function () {
            video.playbackRate = parseFloat(speed.value);
            localStorage.setItem(speedKey, speed.value);
          });

          settingsBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            settingsPanel.hidden = !settingsPanel.hidden;
          });
          document.addEventListener('click', function (e) {
            if (!settingsPanel.hidden && !settingsWrap.contains(e.target)) { settingsPanel.hidden = true; }
          });

          fsBtn.addEventListener('click', function () {
            if (document.fullscreenElement) { document.exitFullscreen(); } else { fullscreenWrap.requestFullscreen(); }
          });
          document.addEventListener('fullscreenchange', function () {
            fsBtn.innerHTML = document.fullscreenElement ? ICON_FULLSCREEN_EXIT : ICON_FULLSCREEN;
          });

          if (meta.prevIndex !== null) {
            prevBtn.addEventListener('click', function () { location.href = watchUrl(meta.prevIndex); });
          }
          if (meta.nextIndex !== null) {
            nextBtn.addEventListener('click', function () { location.href = watchUrl(meta.nextIndex); });
          }

          document.addEventListener('keydown', function (e) {
            var tag = (e.target && e.target.tagName) || '';
            if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') { return; }

            if (e.key === ' ') { e.preventDefault(); togglePlay(); }
            else if (e.key === 'ArrowLeft') { video.currentTime = Math.max(0, video.currentTime - 5); }
            else if (e.key === 'ArrowRight') { video.currentTime = Math.min(video.duration || 1e9, video.currentTime + 5); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); volume.value = String(Math.min(100, Number(volume.value) + 5)); volume.dispatchEvent(new Event('input')); }
            else if (e.key === 'ArrowDown') { e.preventDefault(); volume.value = String(Math.max(0, Number(volume.value) - 5)); volume.dispatchEvent(new Event('input')); }
            else if (e.key === 'f' || e.key === 'F') { fsBtn.click(); }
            else if (e.key === 'm' || e.key === 'M') { muteBtn.click(); }
          });

          // ===== 遠端控制模式:主機正在控制播放時,鎖住手動控制列,改成跟隨輪詢到的狀態播放 =====
          var REMOTE_POLL_MS = 800;
          var REMOTE_DRIFT_THRESHOLD_SEC = 1.5;
          var REMOTE_RESYNC_COOLDOWN_MS = 1000;
          var remoteEnabled = false;
          var lastResyncAt = 0;

          function applyRemoteState(state) {
            if (!state || !state.Enabled) {
              if (remoteEnabled) {
                remoteEnabled = false;
                playerWrap.classList.remove('remote-controlled');
                document.body.classList.remove('remote-fullscreen');
                if (document.fullscreenElement) { document.exitFullscreen().catch(function () {}); }
              }
              return;
            }

            if (!remoteEnabled) {
              remoteEnabled = true;
              playerWrap.classList.add('remote-controlled');
              document.body.classList.add('remote-fullscreen');
              // 這裡是輪詢回應後才觸發,不是使用者直接點擊觸發,不算瀏覽器要求的「使用者手勢」,
              // 原生全螢幕 API 很可能會被擋下來——失敗就靠上面的 remote-fullscreen CSS 頂著,
              // 不需要特別處理這個 rejection。
              if (!document.fullscreenElement) { fullscreenWrap.requestFullscreen().catch(function () {}); }
            }

            if (state.VideoRelativePath && state.VideoRelativePath !== meta.relativePath) {
              location.href = watchUrlForPath(state.VideoRelativePath);
              return;
            }

            if (state.IsPlaying && video.paused) { video.play().catch(function () {}); }
            else if (!state.IsPlaying && !video.paused) { video.pause(); }

            var targetSec = state.PositionMs / 1000;
            var cooldownActive = (Date.now() - lastResyncAt) < REMOTE_RESYNC_COOLDOWN_MS;
            if (!cooldownActive && Math.abs(video.currentTime - targetSec) > REMOTE_DRIFT_THRESHOLD_SEC) {
              video.currentTime = isFinite(video.duration) ? Math.min(targetSec, Math.max(0, video.duration)) : targetSec;
              lastResyncAt = Date.now();
            }

            // 遠端控制中,音量/靜音/播放速度也一併跟主機同步(控制列被鎖住了,觀眾本來就
            // 不能自己調),跟 play/pause 一樣直接套用,不用額外的漂移容忍。
            if (video.playbackRate !== state.PlaybackRate) { video.playbackRate = state.PlaybackRate; }
            if (video.muted !== state.Muted) { video.muted = state.Muted; }
            if (Math.abs(video.volume - state.Volume) > 0.001) { video.volume = state.Volume; }
          }

          applyRemoteState($controlStateJson);
          setInterval(function () {
            fetch('/control/state').then(function (r) { return r.json(); }).then(applyRemoteState).catch(function () {});
          }, REMOTE_POLL_MS);
        })();
        """.trimIndent()
}
