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

    private val VIDEO_EXTENSIONS = setOf(
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

    private fun isVideoFile(name: String): Boolean =
        VIDEO_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())

    /** 依相對路徑排序回傳,讓首頁清單順序穩定,也讓「上一部/下一部」照著這個順序前進有意義。 */
    fun buildManifest(context: Context, treeUri: Uri): List<ScannedEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<ScannedEntry>()
        walk(root, "", out)
        return out.sortedBy { it.relativePath.lowercase() }
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<ScannedEntry>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(child, relativePath, out)
            } else if (child.isFile && isVideoFile(name)) {
                out.add(ScannedEntry(name, relativePath, child.uri, child.length(), child.lastModified()))
            }
        }
    }
}
