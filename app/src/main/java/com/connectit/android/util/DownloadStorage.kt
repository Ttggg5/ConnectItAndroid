package com.connectit.android.util

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 接收到的檔案落地在公用的 `Download/ConnectIt` 資料夾底下(資料夾/多檔案傳輸則再往下多一層
 * 子目錄),讓使用者可以直接在系統的「檔案」App / Downloads 裡看到,不用進到這個 App 專屬、
 * 其他 App 看不到的沙盒目錄裡找。
 *
 * Android 10(API 29)開始的 scoped storage 底下,一般 App 不能再用純檔案路徑寫公用 Download
 * 資料夾,必須改用 [MediaStore.Downloads] 集合;只有 API 26-28 的舊裝置才退回用傳統檔案路徑
 * (搭配 AndroidManifest 裡宣告到 API 28 為止的 WRITE_EXTERNAL_STORAGE 權限)。
 */
object DownloadStorage {
    private const val ROOT_FOLDER = "ConnectIt"

    /** 顯示給使用者看的資料夾位置(不含檔名),兩種實作底下實際落地的地方都是同一個。 */
    const val DISPLAY_ROOT = "Download/$ROOT_FOLDER"

    /** [outputStream] 用完務必呼叫 [markComplete](成功)或 [delete](失敗/取消)之一。 */
    class ReceivedFile(val outputStream: OutputStream, val token: Any, val displayPath: String)

    /** [relativeSubDir] 是 Download/ConnectIt 底下的子目錄(用 '/' 分隔的多層路徑皆可),沒有子目錄用空字串。 */
    fun createFile(resolver: ContentResolver, relativeSubDir: String, fileName: String): ReceivedFile? {
        val relativeDir = buildString {
            append(Environment.DIRECTORY_DOWNLOADS).append('/').append(ROOT_FOLDER)
            if (relativeSubDir.isNotEmpty()) append('/').append(relativeSubDir)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createViaMediaStore(resolver, relativeDir, fileName)
        } else {
            createViaLegacyFile(relativeDir, fileName)
        }
    }

    /** 檔案已完整寫入(收到 file-complete 時呼叫):把 MediaStore 的 IS_PENDING 清掉,讓其他
     * App 看得到這個檔案——寫入過程中維持 pending 狀態,避免別人看到寫到一半的殘破檔案。 */
    fun markComplete(resolver: ContentResolver, token: Any) {
        if (token !is Uri || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(token, values, null, null) }
    }

    /** 傳輸失敗/取消時,刪掉還沒寫完的檔案。 */
    fun delete(resolver: ContentResolver, token: Any) {
        when (token) {
            is Uri -> runCatching { resolver.delete(token, null, null) }
            is File -> runCatching { if (token.exists()) token.delete() }
        }
    }

    private fun createViaMediaStore(resolver: ContentResolver, relativeDir: String, fileName: String): ReceivedFile? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, guessMimeType(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val stream = resolver.openOutputStream(uri) ?: return null
        return ReceivedFile(stream, uri, "$relativeDir/$fileName")
    }

    private fun createViaLegacyFile(relativeDir: String, fileName: String): ReceivedFile? {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        val dir = File(root, relativeDir)
        if (!dir.exists() && !dir.mkdirs()) return null

        var candidate = File(dir, fileName)
        if (candidate.exists()) {
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            var i = 1
            while (candidate.exists()) {
                candidate = File(dir, "$base ($i)$ext")
                i++
            }
        }

        return ReceivedFile(FileOutputStream(candidate), candidate, "$relativeDir/${candidate.name}")
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}
