package com.connectit.android.net

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * 連線建立後,同一個 socket 用的長度前綴訊框協定,對應 Windows 端 ConnectionService.cs:
 *   [4 bytes big-endian 長度(= 1 個型別 byte + 資料長度)][1 byte 型別][資料]
 * 型別 0 = 控制訊息(UTF-8 JSON),型別 1 = 檔案內容區塊(原始 bytes)。
 */
object FrameType {
    const val CONTROL: Int = 0
    const val CHUNK: Int = 1
}

const val FILE_CHUNK_SIZE = 64 * 1024
private const val MAX_FRAME_SIZE = 1 * 1024 * 1024

data class Frame(val type: Int, val payload: ByteArray)

/** 回傳 null 代表剛好在訊框邊界收到 EOF(對方正常關閉連線)。 */
@Throws(EOFException::class)
fun readFrame(input: InputStream): Frame? {
    val header = ByteArray(4)
    if (!readExact(input, header, allowLeadingEof = true)) {
        return null
    }

    val length = ((header[0].toInt() and 0xFF) shl 24) or
        ((header[1].toInt() and 0xFF) shl 16) or
        ((header[2].toInt() and 0xFF) shl 8) or
        (header[3].toInt() and 0xFF)

    if (length <= 0 || length > MAX_FRAME_SIZE) {
        throw java.io.IOException("收到不合法的訊框長度:$length")
    }

    val body = ByteArray(length)
    if (!readExact(input, body, allowLeadingEof = false)) {
        throw EOFException("訊框讀到一半連線就中斷了。")
    }

    return Frame(type = body[0].toInt() and 0xFF, payload = body.copyOfRange(1, body.size))
}

private fun readExact(input: InputStream, buffer: ByteArray, allowLeadingEof: Boolean): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val read = input.read(buffer, offset, buffer.size - offset)
        if (read < 0) {
            if (offset == 0 && allowLeadingEof) {
                return false
            }
            throw EOFException("讀取資料時連線意外中斷。")
        }
        offset += read
    }
    return true
}

/** 呼叫端(ConnectionService)負責用鎖保護同一個 socket 的並行寫入,這裡不做同步。 */
fun writeFrame(output: OutputStream, type: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
    val header = ByteArray(4)
    val total = length + 1
    header[0] = ((total ushr 24) and 0xFF).toByte()
    header[1] = ((total ushr 16) and 0xFF).toByte()
    header[2] = ((total ushr 8) and 0xFF).toByte()
    header[3] = (total and 0xFF).toByte()

    output.write(header)
    output.write(type)
    output.write(payload, offset, length)
    output.flush()
}
