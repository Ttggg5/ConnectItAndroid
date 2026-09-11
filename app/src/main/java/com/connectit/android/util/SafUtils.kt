package com.connectit.android.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.connectit.android.model.FolderEntry

/** 透過 SAF(Storage Access Framework)取得使用者選取的檔案/資料夾的中繼資料,取代 Windows
 * 端直接用檔案系統路徑(File/DirectoryInfo)的做法——Android 的 scoped storage 底下,
 * 使用者選的檔案/資料夾只會拿到 content:// Uri,沒有真正的檔案路徑。 */
object SafUtils {

    /** 查詢單一檔案 Uri 的顯示檔名與大小(位元組)。查不到時檔名退回 Uri 最後一段、大小為 0。 */
    fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }

        return name to size
    }

    /** 遞迴列出使用者選取的資料夾(SAF tree Uri)底下所有檔案,附上相對於根目錄的路徑(用 '/' 分隔)。 */
    fun enumerateTree(context: Context, treeUri: Uri): List<FolderEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val entries = mutableListOf<FolderEntry>()
        walk(root, "", entries)
        return entries
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<FolderEntry>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(child, relativePath, out)
            } else if (child.isFile) {
                out.add(FolderEntry(child.uri, relativePath, child.length()))
            }
        }
    }

    fun displayNameOf(context: Context, treeUri: Uri): String =
        DocumentFile.fromTreeUri(context, treeUri)?.name ?: "資料夾"
}
