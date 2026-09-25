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

    // 型別 byte 單獨讀,資料直接讀進剛好大小的陣列——不用先讀整個 body 再 copyOfRange 切掉
    // 第一個 byte,每個 64KB 區塊省下一次配置與複製。
    val type = input.read()
    if (type < 0) {
        throw EOFException("訊框讀到一半連線就中斷了。")
    }

    val payload = ByteArray(length - 1)
    if (!readExact(input, payload, allowLeadingEof = false)) {
        throw EOFException("訊框讀到一半連線就中斷了。")
    }

    return Frame(type = type, payload = payload)
}

private fun readExact(input: InputStream, buffer: ByteArray, allowLeadingEof: Boolean): Boolean {
    if (buffer.isEmpty()) return true
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

/**
 * 呼叫端(ConnectionService)負責用鎖保護同一個 socket 的並行寫入,這裡不做同步。
 *
 * header/type/payload 合併成單一緩衝區一次寫入,避免拆成多次小的 write() 呼叫各自送出
 * 獨立封包——在 TCP Nagle 演算法與對方 delayed ACK 交互作用下,那樣每個小封包都可能
 * 多花數十毫秒,嚴重拖慢傳輸速度。
 *
 * [scratch] 是呼叫端可重複使用的組裝緩衝區(夠大就直接用,不夠大才另外配置),傳檔時每個
 * 64KB 區塊都要組一次訊框,重用同一塊可以避免持續配置大陣列造成的 GC 壓力。
 */
fun writeFrame(
    output: OutputStream,
    type: Int,
    payload: ByteArray,
    offset: Int = 0,
    length: Int = payload.size,
    scratch: ByteArray? = null,
) {
    val total = length + 1
    val frame = if (scratch != null && scratch.size >= 4 + total) scratch else ByteArray(4 + total)
    frame[0] = ((total ushr 24) and 0xFF).toByte()
    frame[1] = ((total ushr 16) and 0xFF).toByte()
    frame[2] = ((total ushr 8) and 0xFF).toByte()
    frame[3] = (total and 0xFF).toByte()
    frame[4] = type.toByte()
    System.arraycopy(payload, offset, frame, 5, length)

    output.write(frame, 0, 4 + total)
    output.flush()
}
