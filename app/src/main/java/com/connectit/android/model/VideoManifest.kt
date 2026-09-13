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
            VideoManifestResponse(name = obj.getString("Name"), entries = entries)
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
