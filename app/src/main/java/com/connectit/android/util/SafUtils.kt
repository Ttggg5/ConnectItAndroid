package com.connectit.android.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
        val entries = mutableListOf<FolderEntry>()
        walkTree(context.contentResolver, treeUri) { relativePath, file ->
            entries.add(FolderEntry(file.uri, relativePath, file.size))
        }
        return entries
    }

    /** [walkTree]/[listChildren] 查到的單一子項目。 */
    class TreeChild(
        val documentId: String,
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /**
     * 一次查詢拿到 [parentDocumentId] 底下所有子項目的名稱/類型/大小/修改時間。
     *
     * 不用 [DocumentFile.listFiles]:它只查回子項目的 Uri,之後每讀一次 name/isDirectory/isFile/
     * length()/lastModified() 都是一次獨立的跨程序 ContentResolver 查詢——影片庫有上千個檔案時,
     * 光是掃描就要好幾秒到幾十秒。這裡每個資料夾只需要一次查詢。
     */
    fun listChildren(resolver: ContentResolver, treeUri: Uri, parentDocumentId: String): List<TreeChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val out = mutableListOf<TreeChild>()
        resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2)
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                // 對應 DocumentFile.isFile 的判斷:不是資料夾、而且有 MIME 類型。
                if (!isDirectory && mimeType.isNullOrEmpty()) continue
                out.add(
                    TreeChild(
                        documentId = documentId,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                        name = name,
                        isDirectory = isDirectory,
                        size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                        lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                    ),
                )
            }
        }
        return out
    }

    /** 遞迴走訪 [treeUri] 底下所有檔案(不含資料夾本身),[onFile] 收到相對於根目錄的路徑(用 '/' 分隔)。 */
    fun walkTree(resolver: ContentResolver, treeUri: Uri, onFile: (relativePath: String, file: TreeChild) -> Unit) {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return
        walk(resolver, treeUri, rootId, "", onFile)
    }

    private fun walk(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        prefix: String,
        onFile: (String, TreeChild) -> Unit,
    ) {
        for (child in listChildren(resolver, treeUri, parentDocumentId)) {
            val relativePath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                walk(resolver, treeUri, child.documentId, relativePath, onFile)
            } else {
                onFile(relativePath, child)
            }
        }
    }

    fun displayNameOf(context: Context, treeUri: Uri): String =
        DocumentFile.fromTreeUri(context, treeUri)?.name ?: "資料夾"
}
