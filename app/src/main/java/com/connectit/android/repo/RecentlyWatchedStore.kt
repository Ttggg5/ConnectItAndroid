package com.connectit.android.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 「繼續觀看」的一筆紀錄:哪台影片伺服器(用顯示名稱辨識)的哪一部影片、看到哪裡。 */
data class RecentlyWatched(
    val serverName: String,
    val relativePath: String,
    val videoName: String,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAtMillis: Long,
) {
    /** 看了多少比例(0–1),給卡片上的進度條用;不知道總長度時為 0。 */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 記住最近看過的幾部影片,讓「影片」頁的「觀看」分頁頂端可以顯示「繼續觀看」(對應 Windows 端
 * RecentlyWatchedService)。伺服器用顯示名稱比對而不是 host:port——對方重新開啟分享時連接埠通常會變,
 * 名稱通常不變;播放位置也一併記在這裡,不依賴以 host:port 為鍵的 [PlaybackPreferences]。
 *
 * 用 SharedPreferences + 一個 JSON 陣列就夠了:資料量很小(最多 [MAX_ENTRIES] 筆),不需要 DataStore 的非同步讀取。
 */
class RecentlyWatchedStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<RecentlyWatched>> = _entries.asStateFlow()

    /**
     * 記錄(或更新)一筆觀看進度。同一台伺服器上的同一部影片只保留最新一筆;快看完(剩不到 [FINISHED_MARGIN_MS])
     * 的影片視為已看完,從清單移除,不再出現在「繼續觀看」。
     */
    fun record(serverName: String, relativePath: String, videoName: String, positionMs: Long, durationMs: Long, nowMillis: Long = System.currentTimeMillis()) {
        val others = _entries.value.filterNot { it.serverName == serverName && it.relativePath == relativePath }
        val finished = durationMs > 0 && positionMs >= durationMs - FINISHED_MARGIN_MS
        val updated = if (finished || positionMs < MIN_POSITION_MS) {
            others
        } else {
            (listOf(RecentlyWatched(serverName, relativePath, videoName, positionMs, durationMs, nowMillis)) + others)
                .take(MAX_ENTRIES)
        }
        _entries.value = updated
        save(updated)
    }

    fun remove(serverName: String, relativePath: String) {
        val updated = _entries.value.filterNot { it.serverName == serverName && it.relativePath == relativePath }
        _entries.value = updated
        save(updated)
    }

    private fun load(): List<RecentlyWatched> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RecentlyWatched(
                serverName = o.getString("server"),
                relativePath = o.getString("path"),
                videoName = o.getString("name"),
                positionMs = o.getLong("position"),
                durationMs = o.optLong("duration", 0L),
                watchedAtMillis = o.optLong("at", 0L),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(entries: List<RecentlyWatched>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("server", e.serverName)
                    .put("path", e.relativePath)
                    .put("name", e.videoName)
                    .put("position", e.positionMs)
                    .put("duration", e.durationMs)
                    .put("at", e.watchedAtMillis)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "connectit-recently-watched"
        private const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 10

        /** 看不到這麼久的不算「看過」(只是點進去看一眼)。 */
        const val MIN_POSITION_MS = 5_000L

        /** 剩不到這麼久就算看完了。 */
        const val FINISHED_MARGIN_MS = 15_000L

        @Volatile private var instance: RecentlyWatchedStore? = null

        /** 整個 App 共用一份,播放畫面寫入、影片頁讀取的才會是同一份狀態。 */
        fun get(context: Context): RecentlyWatchedStore =
            instance ?: synchronized(this) {
                instance ?: RecentlyWatchedStore(context.applicationContext).also { instance = it }
            }
    }
}
