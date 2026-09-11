package com.connectit.android.model

import org.json.JSONObject

/**
 * 影片伺服器 `GET /manifest` 回傳的清單,對應 Windows 端 VideoManifestResponse.cs /
 * VideoManifestEntry.cs——一樣是 .NET 預設 System.Text.Json 序列化,PascalCase 欄位名稱。
 */
data class VideoManifestEntry(
    val name: String,
    /** 相對於分享根目錄的路徑(用 '/' 分隔),同時是 `/media/{relativePath}`、
     * `/thumbnail/{relativePath}` 的查找鍵。 */
    val relativePath: String,
    val size: Long,
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
                )
            }
            VideoManifestResponse(name = obj.getString("Name"), entries = entries)
        }.getOrNull()
    }
}
