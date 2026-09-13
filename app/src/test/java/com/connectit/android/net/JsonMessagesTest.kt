package com.connectit.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConnectMessage]/[FileControlMessage] 的 JSON 編解碼必須跟 Windows 端(ConnectMessage.cs /
 * FileControlMessage.cs,用 .NET 預設的 System.Text.Json)逐位元組相容,包含 PascalCase 欄位名稱
 * 跟「大小寫需完全相符」的反序列化規則,所以這裡特別驗證欄位名稱字面值,而不是只驗證往返一致。
 */
class JsonMessagesTest {

    @Test
    fun `ConnectMessage toJson uses PascalCase keys`() {
        val json = ConnectMessage(type = "request", deviceName = "小明的手機").toJson()

        assertTrue(json.contains("\"Type\":\"request\""))
        assertTrue(json.contains("\"DeviceName\":\"小明的手機\""))
    }

    @Test
    fun `ConnectMessage toJson omits null deviceName`() {
        val json = ConnectMessage(type = "accept").toJson()

        assertTrue(json.contains("\"Type\":\"accept\""))
        assertTrue(!json.contains("DeviceName"))
    }

    @Test
    fun `ConnectMessage round-trips through parse`() {
        val original = ConnectMessage(type = "request", deviceName = "Pixel 8")

        val parsed = ConnectMessage.parse(original.toJson())

        assertEquals(original, parsed)
    }

    @Test
    fun `ConnectMessage parse returns null for malformed json`() {
        assertNull(ConnectMessage.parse("not json"))
    }

    @Test
    fun `ConnectMessage parse returns null when Type field missing`() {
        assertNull(ConnectMessage.parse("""{"DeviceName":"foo"}"""))
    }

    @Test
    fun `FileControlMessage round-trips all fields through parse`() {
        val original = FileControlMessage(
            type = "file-offer",
            transferId = "abc123",
            fileName = "photo.jpg",
            size = 123456L,
            folderTransferId = "folder789",
            relativePath = "sub/photo.jpg",
            entryIndex = 2,
            totalEntries = 5,
            isBatch = true,
        )

        val parsed = FileControlMessage.parse(original.toJson())

        assertEquals(original, parsed)
    }

    @Test
    fun `FileControlMessage toJson omits absent optional fields`() {
        val json = String(FileControlMessage(type = "file-cancel", transferId = "abc123").toJson(), Charsets.UTF_8)

        assertTrue(json.contains("\"Type\":\"file-cancel\""))
        assertTrue(json.contains("\"TransferId\":\"abc123\""))
        assertTrue(!json.contains("FileName"))
        assertTrue(!json.contains("Size"))
        assertTrue(!json.contains("FolderTransferId"))
        assertTrue(!json.contains("RelativePath"))
        assertTrue(!json.contains("EntryIndex"))
        assertTrue(!json.contains("TotalEntries"))
        assertTrue(!json.contains("IsBatch"))
    }

    @Test
    fun `FileControlMessage parse returns null for malformed bytes`() {
        assertNull(FileControlMessage.parse("{not valid".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `FileControlMessage parse reads a minimal message sent by the Windows side`() {
        // 模擬 Windows 端只帶必要欄位送出的 folder-complete 訊息,確認所有 optional 欄位都能正確解析成 null。
        val bytes = """{"Type":"folder-complete","TransferId":"xyz"}""".toByteArray(Charsets.UTF_8)

        val parsed = FileControlMessage.parse(bytes)

        assertEquals(
            FileControlMessage(type = "folder-complete", transferId = "xyz"),
            parsed,
        )
    }
}
