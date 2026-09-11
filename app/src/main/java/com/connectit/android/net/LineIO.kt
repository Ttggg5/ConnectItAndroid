package com.connectit.android.net

import java.io.InputStream
import java.io.OutputStream

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * 讀取一行以 '\n' 結尾的文字(連線交握用),相容 Windows 端的 StreamReader/StreamWriter 行為:
 * - Windows 端用 `new StreamWriter(stream, Encoding.UTF8)` 送出第一行時,.NET 的 Encoding.UTF8
 *   預設會在該 writer 的第一次寫入加上 UTF-8 BOM(EF BB BF)——這裡要能容忍並跳過這個 BOM。
 * - 行結尾可能是 "\r\n"(Windows StreamWriter 預設 NewLine)或單純 "\n",兩種都要接受。
 * 回傳 null 代表在讀到任何內容之前就先遇到串流結束(EOF)。
 */
fun readLine(input: InputStream): String? {
    val buffer = java.io.ByteArrayOutputStream()
    var readAny = false
    while (true) {
        val b = input.read()
        if (b == -1) {
            return if (readAny) finishLine(buffer) else null
        }
        readAny = true
        if (b == '\n'.code) {
            return finishLine(buffer)
        }
        buffer.write(b)
    }
}

private fun finishLine(buffer: java.io.ByteArrayOutputStream): String {
    var bytes = buffer.toByteArray()
    if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
        bytes = bytes.copyOf(bytes.size - 1)
    }
    if (bytes.size >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]) {
        bytes = bytes.copyOfRange(3, bytes.size)
    }
    return String(bytes, Charsets.UTF_8)
}

/** 寫出一行純 UTF-8 文字(不加 BOM;.NET 的 StreamReader 兩種格式都能正確讀取)。 */
fun writeLine(output: OutputStream, text: String) {
    output.write(text.toByteArray(Charsets.UTF_8))
    output.write('\n'.code)
    output.flush()
}
