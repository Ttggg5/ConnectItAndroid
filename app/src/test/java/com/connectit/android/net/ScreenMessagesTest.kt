package com.connectit.android.net

import com.connectit.android.screen.ScreenFrameDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScreenMessage] 必須跟 Windows 端 ScreenMessage.cs(System.Text.Json、大小寫需完全相符)相容:
 * 驗證 PascalCase 欄位名稱字面值,以及 Windows 端會送出的 `"欄位":null` 也能正確解析。
 */
class ScreenMessagesTest {

    @Test
    fun `toJson uses PascalCase keys and omits nulls`() {
        val json = String(
            ScreenMessage(ScreenMessage.INFO, sessionId = "abc", width = 576, height = 1280, platform = "android", allowControl = true).toJson(),
            Charsets.UTF_8,
        )

        assertTrue(json.contains("\"Type\":\"screen-info\""))
        assertTrue(json.contains("\"SessionId\":\"abc\""))
        assertTrue(json.contains("\"Width\":576"))
        assertTrue(json.contains("\"Height\":1280"))
        assertTrue(json.contains("\"Platform\":\"android\""))
        assertTrue(json.contains("\"AllowControl\":true"))
        assertTrue(!json.contains("Seq"))
        assertTrue(!json.contains("Action"))
    }

    @Test
    fun `input message round-trips through parse`() {
        val original = ScreenMessage(
            ScreenMessage.INPUT, sessionId = "s1", action = ScreenInputAction.WHEEL,
            x = 0.25, y = 0.75, button = 0, deltaY = -2.0, key = "Enter", text = "你好",
        )

        assertEquals(original, ScreenMessage.parse(original.toJson()))
    }

    @Test
    fun `parse accepts explicit nulls sent by Windows`() {
        val json = """{"Type":"screen-ack","SessionId":"s1","Width":null,"Height":null,"Platform":null,"AllowControl":null,"Seq":42,"Action":null,"X":null,"Y":null,"Button":null,"DeltaY":null,"Key":null,"Text":null,"Reason":null}"""

        val parsed = ScreenMessage.parse(json.toByteArray(Charsets.UTF_8))!!

        assertEquals(ScreenMessage.ACK, parsed.type)
        assertEquals(42, parsed.seq)
        assertNull(parsed.width)
        assertNull(parsed.allowControl)
        assertNull(parsed.x)
    }

    @Test
    fun `parse accepts integral numbers for double fields`() {
        // System.Text.Json 把 1.0 序列化成 "1",Kotlin 端要能當成 Double 讀。
        val parsed = ScreenMessage.parse("""{"Type":"screen-input","X":1,"Y":0,"DeltaY":3}""".toByteArray())!!

        assertEquals(1.0, parsed.x!!, 0.0)
        assertEquals(0.0, parsed.y!!, 0.0)
        assertEquals(3.0, parsed.deltaY!!, 0.0)
    }

    @Test
    fun `audio format fields use PascalCase and round-trip`() {
        val original = ScreenMessage(ScreenMessage.INFO, sessionId = "s1", audioSampleRate = 48000, audioChannels = 2)
        val json = String(original.toJson(), Charsets.UTF_8)

        assertTrue(json.contains("\"AudioSampleRate\":48000"))
        assertTrue(json.contains("\"AudioChannels\":2"))
        assertEquals(original, ScreenMessage.parse(original.toJson()))
    }

    @Test
    fun `info without audio fields means no audio`() {
        val parsed = ScreenMessage.parse("""{"Type":"screen-info","SessionId":"s1","Width":1920,"Height":1080}""".toByteArray())!!

        assertNull(parsed.audioSampleRate)
        assertNull(parsed.audioChannels)
    }

    @Test
    fun `parse returns null for malformed json`() {
        assertNull(ScreenMessage.parse("not json".toByteArray()))
        assertNull(ScreenMessage.parse("""{"SessionId":"missing type"}""".toByteArray()))
    }

    @Test
    fun `frame sequence number is big-endian`() {
        val buffer = ByteArray(SCREEN_FRAME_HEADER_SIZE)
        ScreenFrameDecoder.writeSeq(buffer, 0x01020304)

        assertEquals(listOf<Byte>(1, 2, 3, 4), buffer.toList())
        assertEquals(0x01020304, ScreenFrameDecoder.readSeq(buffer))
    }
}
