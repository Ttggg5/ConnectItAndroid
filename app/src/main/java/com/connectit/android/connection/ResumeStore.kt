package com.connectit.android.connection

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.connectit.android.model.FolderEntry
import com.connectit.android.util.DownloadStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 一筆可以續傳的紀錄(接收端):連線中斷時保留下來的部分檔案。[token] 是 [DownloadStorage.ReceivedFile.token]
 * 序列化後的字串(見 [DownloadStorage.tokenToString]),以「哪台裝置([peerDeviceId])送來的哪個檔案([resumeKey])」比對。
 */
data class ResumeEntry(
    val peerDeviceId: String,
    val resumeKey: String,
    val token: String,
    val displayPath: String,
    val savedAtMillis: Long,
)

/**
 * 傳輸中斷後續傳的接收端紀錄(對應 Windows 端 ResumeStore.cs),存成 App 私有資料夾裡的 resume-state.json。
 * 傳送端在 file-offer 帶上 ResumeKey(見 [ResumeKeys]);同一台裝置重新連線後送來相同 ResumeKey 的提議,
 * 接收端就接著寫進保留的部分檔案,file-accept 帶上 Offset 告訴傳送端從哪裡開始送。
 * 資料夾傳輸在 Android 這邊不用另外記:同一個資料夾名稱的檔案本來就落在同一個子目錄。
 * 超過 [MAX_AGE_MS] 沒有續傳的紀錄會被清掉(連同部分檔案)。
 */
class ResumeStore(private val file: File) {
    private val lock = Any()
    private val entries: MutableList<ResumeEntry> = load().toMutableList()

    val all: List<ResumeEntry> get() = synchronized(lock) { entries.toList() }

    fun find(peerDeviceId: String?, resumeKey: String?): ResumeEntry? {
        if (peerDeviceId == null || resumeKey == null) return null
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            entries.firstOrNull { it.peerDeviceId == peerDeviceId && it.resumeKey == resumeKey && now - it.savedAtMillis <= MAX_AGE_MS }
        }
    }

    fun save(entry: ResumeEntry) = synchronized(lock) {
        entries.removeAll { it.peerDeviceId == entry.peerDeviceId && it.resumeKey == entry.resumeKey }
        entries += entry
        persist()
    }

    fun remove(peerDeviceId: String?, resumeKey: String?) {
        if (peerDeviceId == null || resumeKey == null) return
        synchronized(lock) {
            if (entries.removeAll { it.peerDeviceId == peerDeviceId && it.resumeKey == resumeKey }) persist()
        }
    }

    /** 清掉過期的紀錄,並刪除它們保留的部分檔案。 */
    fun purgeExpired(resolver: ContentResolver) {
        val now = System.currentTimeMillis()
        val expired = synchronized(lock) {
            val old = entries.filter { now - it.savedAtMillis > MAX_AGE_MS }
            if (old.isEmpty()) return
            entries.removeAll(old.toSet())
            persist()
            old
        }
        expired.forEach { DownloadStorage.delete(resolver, DownloadStorage.tokenFromString(it.token)) }
    }

    private fun load(): List<ResumeEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            ResumeEntry(
                peerDeviceId = obj.getString("PeerDeviceId"),
                resumeKey = obj.getString("ResumeKey"),
                token = obj.getString("Token"),
                displayPath = obj.optString("DisplayPath"),
                savedAtMillis = obj.getLong("SavedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("PeerDeviceId", it.peerDeviceId)
                    .put("ResumeKey", it.resumeKey)
                    .put("Token", it.token)
                    .put("DisplayPath", it.displayPath)
                    .put("SavedAt", it.savedAtMillis)
            )
        }
        runCatching { file.writeText(array.toString()) }
    }

    companion object {
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        fun forContext(context: Context) = ResumeStore(File(context.filesDir, "resume-state.json"))
    }
}

/**
 * 傳送端產生 ResumeKey:同一個來源檔案(Uri、大小、修改時間都沒變)每次都算出同一個值,
 * 檔案被修改過就會不同,接收端就不會把新內容接在舊的部分檔案後面。
 */
object ResumeKeys {
    fun forFile(resolver: ContentResolver, uri: Uri, size: Long): String =
        hash("file|$uri|$size|${lastModified(resolver, uri)}")

    /** 資料夾:名稱 + 裡面所有檔案的相對路徑(第一次送出時算好,續傳時沿用同一個值)。 */
    fun forFolder(folderName: String, entries: List<FolderEntry>): String =
        hash("folder|$folderName|${entries.map { it.relativePath }.sorted().joinToString("|")}")

    private fun lastModified(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    internal fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
}

/**
 * 傳送端:連線中斷時還沒送完的內容,同一台裝置重新連線後可以用 [PeerConnection.resumeSend] 繼續。
 * 資料夾傳輸記住資料夾名稱、剩下還沒送完的檔案與資料夾的 ResumeKey;單檔/多檔記住還沒送完的檔案。
 */
data class PendingSend(
    val displayName: String,
    val files: List<Triple<Uri, String, Long>>,
    val folderName: String? = null,
    val folderEntries: List<FolderEntry> = emptyList(),
    val folderResumeKey: String? = null,
)
