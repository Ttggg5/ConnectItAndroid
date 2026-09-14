package com.connectit.android.model

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 影片伺服器 `GET /manifest` 回傳的清單,對應 Windows 端 VideoManifestResponse.cs /
 * VideoManifestEntry.cs——一樣是 .NET 預設 System.Text.Json 序列化,PascalCase 欄位名稱,
 * `Modified` 是 ISO-8601 格式的 UTC 時間字串。
 */
data class VideoManifestEntry(
    val name: String,
    /** 相對於分享根目錄的路徑(用 '/' 分隔),同時是 `/media/{relativePath}`、
     * `/thumbnail/{relativePath}` 的查找鍵。 */
    val relativePath: String,
    val size: Long,
    val modifiedEpochMillis: Long?,
)

data class VideoManifestResponse(
    val name: String,
    val entries: List<VideoManifestEntry>,
    /** 主機是否開了遠端控制模式——只是輪詢 `/control/state` 前的初始提示,真正即時的值以那個
     * 端點為準(host 可能在觀眾已經進入觀看頁之後才切換模式)。 */
    val remoteControlEnabled: Boolean,
) {
    companion object {
        fun parse(json: String): VideoManifestResponse? = runCatching {
            val obj = JSONObject(json)
            val entriesJson = obj.getJSONArray("Entries")
            val entries = (0 until entriesJson.length()).map { i ->
                val entry = entriesJson.getJSONObject(i)
                VideoManifestEntry(
                    name = entry.getString("Name"),
                    relativePath = entry.getString("RelativePath"),
                    size = entry.getLong("Size"),
                    modifiedEpochMillis = entry.optString("Modified", "").let { parseIsoInstant(it) },
                )
            }
            VideoManifestResponse(
                name = obj.getString("Name"),
                entries = entries,
                remoteControlEnabled = obj.optBoolean("RemoteControlEnabled", false),
            )
        }.getOrNull()

        /** .NET 預設序列化 DateTime 依 Kind 而定,可能有(Z)也可能沒有時區資訊,依序嘗試幾種常見格式。 */
        private fun parseIsoInstant(text: String): Long? {
            if (text.isBlank()) return null
            return runCatching { Instant.parse(text).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
                .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
                .getOrNull()
        }
    }
}

/** 影片伺服器 `GET /control/state` 回傳的即時播放狀態,對應 Android/Windows 兩端主機共用的
 * PlaybackControlState 序列化形狀(見 video/PlaybackControlState.kt 的 `toJson()`)。 */
data class ControlStateSnapshot(
    val enabled: Boolean,
    val videoRelativePath: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val playbackRate: Double,
    val volume: Double,
    val muted: Boolean,
    val version: Long,
) {
    companion object {
        fun parse(json: String): ControlStateSnapshot? = runCatching {
            val obj = JSONObject(json)
            ControlStateSnapshot(
                enabled = obj.getBoolean("Enabled"),
                videoRelativePath = if (obj.isNull("VideoRelativePath")) null else obj.optString("VideoRelativePath"),
                isPlaying = obj.getBoolean("IsPlaying"),
                positionMs = obj.getLong("PositionMs"),
                playbackRate = obj.optDouble("PlaybackRate", 1.0),
                volume = obj.optDouble("Volume", 1.0),
                muted = obj.optBoolean("Muted", false),
                version = obj.optLong("Version", 0),
            )
        }.getOrNull()
    }
}
