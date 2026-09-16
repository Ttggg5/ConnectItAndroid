package com.connectit.android.video

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 掃描使用者透過 SAF 選取的資料夾(tree Uri),找出裡面所有影片檔案,對應 Windows 端的
 * VideoLibraryScanner.cs——差別在於 Android 這邊拿到的是 content:// Uri 而不是檔案系統路徑,
 * 所以「相對路徑 -> 完整路徑」的查找字典換成「相對路徑 -> Uri」。
 */
object VideoLibraryScanner {

    /** 內建支援的副檔名(不含開頭的 '.'),對應 Windows 端 VideoLibraryScanner.VideoExtensions。
     * public 是因為設定頁「額外支援的副檔名」要拿它判斷使用者輸入的副檔名是不是已經內建支援。 */
    val VideoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "mpg", "mpeg",
    )

    data class ScannedEntry(
        val name: String,
        /** 相對於分享根目錄的路徑(用 '/' 分隔),同時是 `/media/{relativePath}`、
         * `/thumbnail/{relativePath}` 的查找鍵。 */
        val relativePath: String,
        val uri: Uri,
        val size: Long,
        val modifiedEpochMillis: Long,
    )

    private fun isVideoFile(name: String, extraExtensions: Set<String>): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return VideoExtensions.contains(extension) || extraExtensions.contains(extension)
    }

    /** [extraExtensions] 使用者在設定頁額外加入、內建清單以外的副檔名(見
     * [com.connectit.android.repo.AppSettings.videoExtraExtensions]),不含開頭的 '.'。
     * 依相對路徑排序回傳,讓首頁清單順序穩定,也讓「上一部/下一部」照著這個順序前進有意義。 */
    fun buildManifest(context: Context, treeUri: Uri, extraExtensions: Collection<String> = emptyList()): List<ScannedEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val normalizedExtra = extraExtensions.map { it.removePrefix(".").lowercase() }.toSet()
        val out = mutableListOf<ScannedEntry>()
        walk(root, "", out, normalizedExtra)
        return out.sortedBy { it.relativePath.lowercase() }
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<ScannedEntry>, extraExtensions: Set<String>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(child, relativePath, out, extraExtensions)
            } else if (child.isFile && isVideoFile(name, extraExtensions)) {
                out.add(ScannedEntry(name, relativePath, child.uri, child.length(), child.lastModified()))
            }
        }
    }

    /** 逗號/分號/空白/換行分隔的副檔名清單(有沒有前置 "." 都可以),正規化成小寫、不含開頭 "."、
     * 去重,並跳過已經內建支援的副檔名,對應 Windows 端 VideoServerSettingsService.ParseExtensions。 */
    fun parseExtraExtensions(rawText: String): List<String> {
        val result = mutableListOf<String>()
        for (token in rawText.split(',', ';', ' ', '\t', '\r', '\n')) {
            val ext = token.trim().removePrefix(".").lowercase()
            if (ext.isEmpty() || VideoExtensions.contains(ext) || result.contains(ext)) continue
            result.add(ext)
        }
        return result
    }
}
