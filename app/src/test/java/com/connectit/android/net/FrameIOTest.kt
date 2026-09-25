package com.connectit.android.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class FrameIOTest {

    @Test
    fun `writeFrame 輸出格式為 4 bytes big-endian 長度加型別 byte 加資料`() {
        val out = ByteArrayOutputStream()
        writeFrame(out, FrameType.CHUNK, byteArrayOf(9, 8, 7))

        assertArrayEquals(byteArrayOf(0, 0, 0, 4, 1, 9, 8, 7), out.toByteArray())
    }

    @Test
    fun `使用 scratch 緩衝區時只寫出實際訊框長度`() {
        val out = ByteArrayOutputStream()
        val scratch = ByteArray(1024) { 0x55 }
        writeFrame(out, FrameType.CONTROL, byteArrayOf(1, 2, 3, 4), offset = 1, length = 2, scratch = scratch)

        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 0, 2, 3), out.toByteArray())
    }

    @Test
    fun `連續多個訊框可以依序讀回`() {
        val out = ByteArrayOutputStream()
        val scratch = ByteArray(FILE_CHUNK_SIZE + 5)
        val big = ByteArray(FILE_CHUNK_SIZE) { (it % 251).toByte() }
        writeFrame(out, FrameType.CONTROL, "{}".toByteArray(), scratch = scratch)
        writeFrame(out, FrameType.CHUNK, big, scratch = scratch)
        writeFrame(out, FrameType.CHUNK, ByteArray(0), scratch = scratch)

        val input = ByteArrayInputStream(out.toByteArray())
        val first = readFrame(input)!!
        assertEquals(FrameType.CONTROL, first.type)
        assertArrayEquals("{}".toByteArray(), first.payload)

        val second = readFrame(input)!!
        assertEquals(FrameType.CHUNK, second.type)
        assertArrayEquals(big, second.payload)

        val third = readFrame(input)!!
        assertEquals(FrameType.CHUNK, third.type)
        assertEquals(0, third.payload.size)

        assertNull(readFrame(input))
    }

    @Test(expected = EOFException::class)
    fun `訊框讀到一半中斷時丟出 EOFException`() {
        readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 4, 1, 9)))
    }
}
