package com.connectit.android.net

import org.json.JSONObject

/**
 * 連線交握 / 檔案傳輸控制訊息的 JSON 編解碼。
 *
 * 對應 Windows 端 ConnectMessage.cs / FileControlMessage.cs:那邊用 .NET 預設的
 * System.Text.Json(未指定 JsonSerializerOptions)序列化,所以欄位名稱是原始的
 * PascalCase(例如 "Type"、"TransferId"),且反序列化預設「大小寫需完全相符」——
 * 這裡的 JSON key 必須跟 Windows 那邊逐字一致,不能改成 camelCase。
 */

/** "request" | "accept" | "reject" */
data class ConnectMessage(val type: String, val deviceName: String? = null) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("Type", type)
        if (deviceName != null) obj.put("DeviceName", deviceName)
        return obj.toString()
    }

    companion object {
        fun parse(text: String): ConnectMessage? = runCatching {
            val obj = JSONObject(text)
            ConnectMessage(
                type = obj.getString("Type"),
                deviceName = obj.optStringOrNull("DeviceName"),
            )
        }.getOrNull()
    }
}

/**
 * "file-offer" | "file-accept" | "file-reject" | "file-cancel" | "file-complete" |
 * "folder-offer" | "folder-accept" | "folder-reject" | "folder-cancel" | "folder-complete"
 */
data class FileControlMessage(
    val type: String,
    val transferId: String? = null,
    val fileName: String? = null,
    val size: Long? = null,
    val folderTransferId: String? = null,
    val relativePath: String? = null,
    val entryIndex: Int? = null,
    val totalEntries: Int? = null,
    val isBatch: Boolean? = null,
) {
    fun toJson(): ByteArray {
        val obj = JSONObject()
        obj.put("Type", type)
        if (transferId != null) obj.put("TransferId", transferId)
        if (fileName != null) obj.put("FileName", fileName)
        if (size != null) obj.put("Size", size)
        if (folderTransferId != null) obj.put("FolderTransferId", folderTransferId)
        if (relativePath != null) obj.put("RelativePath", relativePath)
        if (entryIndex != null) obj.put("EntryIndex", entryIndex)
        if (totalEntries != null) obj.put("TotalEntries", totalEntries)
        if (isBatch != null) obj.put("IsBatch", isBatch)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun parse(bytes: ByteArray): FileControlMessage? = runCatching {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            FileControlMessage(
                type = obj.getString("Type"),
                transferId = obj.optStringOrNull("TransferId"),
                fileName = obj.optStringOrNull("FileName"),
                size = if (obj.has("Size") && !obj.isNull("Size")) obj.getLong("Size") else null,
                folderTransferId = obj.optStringOrNull("FolderTransferId"),
                relativePath = obj.optStringOrNull("RelativePath"),
                entryIndex = if (obj.has("EntryIndex") && !obj.isNull("EntryIndex")) obj.getInt("EntryIndex") else null,
                totalEntries = if (obj.has("TotalEntries") && !obj.isNull("TotalEntries")) obj.getInt("TotalEntries") else null,
                isBatch = if (obj.has("IsBatch") && !obj.isNull("IsBatch")) obj.getBoolean("IsBatch") else null,
            )
        }.getOrNull()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
