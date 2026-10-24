package com.connectit.android.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** GitHub 最新 Release 的資訊(只保留自我更新需要的欄位)。 */
data class ReleaseInfo(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** GitHub 提供的 `sha256:...` 摘要;舊的 Release 可能沒有。 */
    val digest: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    /** [progress] 0–1,伺服器沒給檔案大小時為 null。 */
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val apk: File) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState
}

/**
 * 自我更新:向 GitHub Releases 查詢最新版本(CI 在 master 每次建置都會發佈 `v1.0.{建置編號}` 的 Release,
 * 附上 release APK,見 .github/workflows/build-apk.yml),tag 最後一段就是 versionCode,比已安裝的大
 * 才算有新版本。下載到 cacheDir/updates,再透過 FileProvider 交給系統安裝程式——APK 一律用同一把
 * release 金鑰簽章(見 app/build.gradle.kts),系統才會當成「更新」直接覆蓋安裝、保留資料。
 *
 * Android 不允許 App 靜默安裝自己,最後一步一定是系統的安裝確認畫面;第一次還需要使用者允許
 * 「安裝不明應用程式」,[install] 會在沒有權限時改帶到那個設定頁。
 */
class AppUpdater(
    private val context: Context,
    /** 服務的 scope:下載不跟著畫面走,使用者離開設定頁也會繼續。 */
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersionCode: Long = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(0L)

    val currentVersionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** 給畫面上的按鈕用:在服務的 scope 裡檢查/下載,不會因為離開畫面而中斷。 */
    fun requestCheck() {
        scope.launch { check() }
    }

    fun startDownload(release: ReleaseInfo) {
        scope.launch { downloadAndInstall(release) }
    }

    /** 查詢有沒有新版本,結果寫進 [state];回傳找到的新版本(沒有或失敗則為 null)。 */
    suspend fun check(): ReleaseInfo? {
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return null
        _state.value = UpdateState.Checking
        return try {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            if (release != null && release.versionCode > currentVersionCode) {
                _state.value = UpdateState.Available(release)
                release
            } else {
                _state.value = UpdateState.UpToDate
                null
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("檢查更新失敗,請確認網路連線後再試一次。", null)
            null
        }
    }

    /** 下載新版本 APK,完成後狀態變成 [UpdateState.ReadyToInstall] 並直接開啟系統安裝畫面。 */
    suspend fun downloadAndInstall(release: ReleaseInfo) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(release, null)
        val apk = try {
            withContext(Dispatchers.IO) { download(release) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("下載更新失敗,請稍後再試一次。", release)
            return
        }
        _state.value = UpdateState.ReadyToInstall(release, apk)
        install(apk)
    }

    /**
     * 開啟系統安裝畫面。還沒允許「安裝不明應用程式」時改開那個設定頁,回傳 false;
     * 使用者允許後回到 App 再按一次「安裝」即可(狀態維持在 [UpdateState.ReadyToInstall])。
     */
    fun install(apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(settingsIntent)
            } catch (e: ActivityNotFoundException) {
                // 部分 Android TV 沒有這個設定頁,只能請使用者自己到系統設定的安全性頁面開啟。
                (_state.value as? UpdateState.ReadyToInstall)?.let {
                    _state.value = UpdateState.Failed("請先到系統設定允許 ConnectIt 安裝應用程式,再回來重試。", it.release)
                }
            }
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val connection = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ConnectIt-Android/$currentVersionName")
        }
        try {
            // 還沒發佈過任何 Release。
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseRelease(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(release: ReleaseInfo): File {
        val directory = File(context.cacheDir, UPDATE_CACHE_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(directory, "ConnectIt-${release.versionCode}.apk")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ConnectIt-Android/$currentVersionName")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        if (total > 0) {
                            val percent = (received * 100 / total).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                _state.value = UpdateState.Downloading(release, (received.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
            verifyDigest(digest.digest(), release.digest)
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/Ttggg5/ConnectItAndroid/releases/latest"
        const val RELEASE_ASSET_NAME = "ConnectIt.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val UPDATE_CACHE_DIR = "updates"

        /** "v1.0.42" → 42:CI 的 tag 是 `v1.0.{建置編號}`,建置編號就是 versionCode(見 app/build.gradle.kts)。 */
        fun parseVersionCode(tag: String?): Long? =
            tag?.trim()?.trimStart('v', 'V')?.split('.')?.lastOrNull()?.toLongOrNull()

        /** 解析 GitHub `releases/latest` 的回應;tag 格式不對或沒有 APK 附件時回傳 null。 */
        fun parseRelease(json: String): ReleaseInfo? {
            val root = JSONObject(json)
            val tag = root.optString("tag_name")
            val versionCode = parseVersionCode(tag) ?: return null
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (!asset.optString("name").equals(RELEASE_ASSET_NAME, ignoreCase = true)) continue
                val url = asset.optString("browser_download_url").takeIf { it.isNotEmpty() } ?: return null
                return ReleaseInfo(
                    versionCode = versionCode,
                    versionName = tag.trimStart('v', 'V'),
                    notes = root.optString("body"),
                    apkUrl = url,
                    apkSizeBytes = asset.optLong("size"),
                    digest = asset.optString("digest").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
            return null
        }

        /** 有 `sha256:...` 摘要時驗證下載內容,避免裝到不完整或被竄改的 APK。 */
        internal fun verifyDigest(actual: ByteArray, expected: String?) {
            val prefix = "sha256:"
            if (expected == null || !expected.startsWith(prefix, ignoreCase = true)) return
            val actualHex = actual.joinToString("") { "%02x".format(it) }
            check(actualHex.equals(expected.substring(prefix.length), ignoreCase = true)) { "APK 驗證失敗" }
        }
    }
}

/** 設定頁(手機/TV 共用)顯示的狀態文字。 */
fun UpdateState.statusText(): String = when (this) {
    UpdateState.Idle -> "啟動時會自動檢查有沒有新版本。"
    UpdateState.Checking -> "正在檢查更新…"
    UpdateState.UpToDate -> "已經是最新版本。"
    is UpdateState.Available -> "有新版本 ${release.versionName} 可以更新。"
    is UpdateState.Downloading -> "正在下載 ${release.versionName}…" + (progress?.let { " ${(it * 100).toInt()}%" } ?: "")
    is UpdateState.ReadyToInstall -> "${release.versionName} 已下載完成,按「安裝」並在系統畫面確認即可更新。"
    is UpdateState.Failed -> message
}
