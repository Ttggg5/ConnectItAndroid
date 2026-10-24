package com.connectit.android.repo

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.connectit.android.video.VideoLibraryScanner
import com.connectit.android.video.VideoServerPlaybackOptions
import com.connectit.android.video.VideoSort
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import java.util.UUID
import kotlinx.coroutines.flow.map

enum class AppThemeMode { LIGHT, DARK, AUTO }

/** 信任清單裡的一台裝置。[name] 只是顯示用,比對一律用 [fingerprint](對方 TLS 憑證的指紋)。 */
data class TrustedDevice(val fingerprint: String, val name: String, val deviceId: String?)

data class AppSettings(
    val deviceName: String,
    val themeMode: AppThemeMode,
    /** 收到傳輸/斷線等事件時是否跳出系統通知(連線請求對話框不受影響,只影響提醒用的通知)。 */
    val notificationsEnabled: Boolean = true,
    /** 已標記為信任的裝置(比對 [com.connectit.android.model.ConnectionRequest.requesterFingerprint]),
     * 收到這些裝置的連線請求時不再跳出確認對話框,直接自動接受。只認憑證指紋,改名或冒名都不會被自動接受;
     * 舊版只存名稱(trusted_device_names),沒辦法換算成指紋,升級後需要重新配對一次。 */
    val trustedDevices: List<TrustedDevice> = emptyList(),
    /** 使用者透過 SAF 選取的自訂接收資料夾(content:// tree Uri 字串),null 代表使用預設的
     * 公用 Download/ConnectIt 資料夾。 */
    val customDownloadFolderUri: String? = null,
    /** 主動連線逾時秒數(對應 [com.connectit.android.connection.ConnectionEngine] 的 CONNECT_TIMEOUT_MS)。 */
    val connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    /** 監聽用的 TCP 連接埠,0 代表由系統自動指派。 */
    val preferredPort: Int = 0,
    /** 上一次選取用來分享影片的 SAF 資料夾(content:// tree Uri 字串),只是記住上次選擇方便
     * 下次快速重新分享,不會在 App/服務啟動時自動開始分享。 */
    val videoShareFolderUri: String? = null,
    /** 以下對應 Windows 端 VideoServerSettingsService:下次按「開始分享」影片時套用的預設值
     * (清單排序方式、播放器初始行為、掃描資料夾時額外要當作影片的副檔名),不會在伺服器執行中即時生效。 */
    val videoDefaultSort: String = VideoSort.DEFAULT_VALUE,
    val videoAutoplayNext: Boolean = VideoServerPlaybackOptions.DEFAULT_AUTOPLAY_NEXT,
    val videoShuffle: Boolean = VideoServerPlaybackOptions.DEFAULT_SHUFFLE,
    val videoDefaultVolumePercent: Int = VideoServerPlaybackOptions.DEFAULT_VOLUME_PERCENT,
    val videoDefaultSpeed: Double = VideoServerPlaybackOptions.DEFAULT_PLAYBACK_SPEED,
    /** 使用者額外加入的副檔名(內建清單以外),已正規化成小寫、不含開頭 '.'、不重複。 */
    val videoExtraExtensions: List<String> = emptyList(),
    /** 首次啟動引導是否已經完成(或被略過)。預設 true:DataStore 還沒讀出來之前不要閃一下引導畫面,
     * 實際值由 [SettingsRepository.settings] 依儲存的內容決定(沒存過就是 false)。 */
    val onboardingCompleted: Boolean = true,
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10
        const val MIN_CONNECT_TIMEOUT_SECONDS = 3
        const val MAX_CONNECT_TIMEOUT_SECONDS = 120
    }
}

private val Context.dataStore by preferencesDataStore(name = "connectit-settings")

/** 對應 Windows 端的裝置名稱設定與 ThemeService,統一存在 DataStore 裡。
 *
 * Windows 端另外還有「自動搜尋秒數」設定,但那是用來配合 Makaretu.Dns 需要定期重送查詢的
 * 保險機制;Android 的 NsdManager 探索本身就是持續推播 onServiceFound/onServiceLost,
 * 不需要手動重複查詢,所以這裡不需要對應的設定項目。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val TRUSTED_DEVICES_V2 = stringPreferencesKey("trusted_devices_v2")
        val CUSTOM_DOWNLOAD_FOLDER_URI = stringPreferencesKey("custom_download_folder_uri")
        val CONNECT_TIMEOUT_SECONDS = intPreferencesKey("connect_timeout_seconds")
        val PREFERRED_PORT = intPreferencesKey("preferred_port")
        val VIDEO_SHARE_FOLDER_URI = stringPreferencesKey("video_share_folder_uri")
        val VIDEO_DEFAULT_SORT = stringPreferencesKey("video_default_sort")
        val VIDEO_AUTOPLAY_NEXT = booleanPreferencesKey("video_autoplay_next")
        val VIDEO_SHUFFLE = booleanPreferencesKey("video_shuffle")
        val VIDEO_DEFAULT_VOLUME_PERCENT = intPreferencesKey("video_default_volume_percent")
        val VIDEO_DEFAULT_SPEED = doublePreferencesKey("video_default_speed")
        val VIDEO_EXTRA_EXTENSIONS = stringPreferencesKey("video_extra_extensions")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            deviceName = prefs[Keys.DEVICE_NAME] ?: defaultDeviceName(),
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() } ?: AppThemeMode.AUTO,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            trustedDevices = parseTrustedDevices(prefs[Keys.TRUSTED_DEVICES_V2]),
            customDownloadFolderUri = prefs[Keys.CUSTOM_DOWNLOAD_FOLDER_URI],
            connectTimeoutSeconds = (prefs[Keys.CONNECT_TIMEOUT_SECONDS] ?: AppSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS)
                .coerceIn(AppSettings.MIN_CONNECT_TIMEOUT_SECONDS, AppSettings.MAX_CONNECT_TIMEOUT_SECONDS),
            preferredPort = prefs[Keys.PREFERRED_PORT] ?: 0,
            videoShareFolderUri = prefs[Keys.VIDEO_SHARE_FOLDER_URI],
            videoDefaultSort = prefs[Keys.VIDEO_DEFAULT_SORT]?.takeIf { sort -> VideoSort.entries.any { it.value == sort } }
                ?: VideoSort.DEFAULT_VALUE,
            videoAutoplayNext = prefs[Keys.VIDEO_AUTOPLAY_NEXT] ?: VideoServerPlaybackOptions.DEFAULT_AUTOPLAY_NEXT,
            videoShuffle = prefs[Keys.VIDEO_SHUFFLE] ?: VideoServerPlaybackOptions.DEFAULT_SHUFFLE,
            videoDefaultVolumePercent = (prefs[Keys.VIDEO_DEFAULT_VOLUME_PERCENT] ?: VideoServerPlaybackOptions.DEFAULT_VOLUME_PERCENT)
                .coerceIn(0, 100),
            videoDefaultSpeed = prefs[Keys.VIDEO_DEFAULT_SPEED]?.takeIf { VideoServerPlaybackOptions.VALID_PLAYBACK_SPEEDS.contains(it) }
                ?: VideoServerPlaybackOptions.DEFAULT_PLAYBACK_SPEED,
            videoExtraExtensions = VideoLibraryScanner.parseExtraExtensions(prefs[Keys.VIDEO_EXTRA_EXTENSIONS] ?: ""),
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
        )
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[Keys.DEVICE_NAME] = name.ifBlank { defaultDeviceName() } }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    /** 加入(或更新)一台信任裝置;同一台裝置(相同指紋或相同 DeviceId)只留最新的一筆。 */
    suspend fun trustDevice(fingerprint: String, name: String, deviceId: String?) {
        if (fingerprint.isBlank()) return
        context.dataStore.edit { prefs ->
            val kept = parseTrustedDevices(prefs[Keys.TRUSTED_DEVICES_V2]).filterNot {
                it.fingerprint.equals(fingerprint, ignoreCase = true) || (deviceId != null && it.deviceId == deviceId)
            }
            prefs[Keys.TRUSTED_DEVICES_V2] = encodeTrustedDevices(kept + TrustedDevice(fingerprint, name, deviceId))
        }
    }

    suspend fun untrustDevice(fingerprint: String) {
        context.dataStore.edit { prefs ->
            val kept = parseTrustedDevices(prefs[Keys.TRUSTED_DEVICES_V2]).filterNot { it.fingerprint.equals(fingerprint, ignoreCase = true) }
            prefs[Keys.TRUSTED_DEVICES_V2] = encodeTrustedDevices(kept)
        }
    }

    /** [uriString] 傳 null 代表清除自訂資料夾、還原成預設的 Download/ConnectIt。 */
    suspend fun setCustomDownloadFolderUri(uriString: String?) {
        context.dataStore.edit {
            if (uriString == null) it.remove(Keys.CUSTOM_DOWNLOAD_FOLDER_URI) else it[Keys.CUSTOM_DOWNLOAD_FOLDER_URI] = uriString
        }
    }

    suspend fun setConnectTimeoutSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(AppSettings.MIN_CONNECT_TIMEOUT_SECONDS, AppSettings.MAX_CONNECT_TIMEOUT_SECONDS)
        context.dataStore.edit { it[Keys.CONNECT_TIMEOUT_SECONDS] = clamped }
    }

    /** [port] 0 代表交由系統自動指派;其餘必須落在 1024-65535 之間(避免需要額外權限的特權連接埠)。 */
    suspend fun setPreferredPort(port: Int) {
        val normalized = if (port == 0) 0 else port.coerceIn(1024, 65535)
        context.dataStore.edit { it[Keys.PREFERRED_PORT] = normalized }
    }

    suspend fun setVideoShareFolderUri(uriString: String?) {
        context.dataStore.edit {
            if (uriString == null) it.remove(Keys.VIDEO_SHARE_FOLDER_URI) else it[Keys.VIDEO_SHARE_FOLDER_URI] = uriString
        }
    }

    suspend fun setVideoDefaultSort(sort: String) {
        val normalized = if (VideoSort.entries.any { it.value == sort }) sort else VideoSort.DEFAULT_VALUE
        context.dataStore.edit { it[Keys.VIDEO_DEFAULT_SORT] = normalized }
    }

    suspend fun setVideoAutoplayNext(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIDEO_AUTOPLAY_NEXT] = enabled }
    }

    suspend fun setVideoShuffle(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIDEO_SHUFFLE] = enabled }
    }

    suspend fun setVideoDefaultVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        context.dataStore.edit { it[Keys.VIDEO_DEFAULT_VOLUME_PERCENT] = clamped }
    }

    suspend fun setVideoDefaultSpeed(speed: Double) {
        val normalized = if (VideoServerPlaybackOptions.VALID_PLAYBACK_SPEEDS.contains(speed)) {
            speed
        } else {
            VideoServerPlaybackOptions.DEFAULT_PLAYBACK_SPEED
        }
        context.dataStore.edit { it[Keys.VIDEO_DEFAULT_SPEED] = normalized }
    }

    /** [rawText] 逗號/分號/空白分隔的副檔名清單(見 [VideoLibraryScanner.parseExtraExtensions]),
     * 回傳正規化後實際套用的清單,方便呼叫端把輸入框內容替換成正規化後的樣子。 */
    suspend fun setVideoExtraExtensions(rawText: String): List<String> {
        val normalized = VideoLibraryScanner.parseExtraExtensions(rawText)
        context.dataStore.edit { it[Keys.VIDEO_EXTRA_EXTENSIONS] = normalized.joinToString(",") }
        return normalized
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    fun defaultDeviceName(): String = Build.MODEL ?: "Android"

    companion object {
        /** 信任清單存成 JSON 陣列字串:[{"Fingerprint":..,"Name":..,"DeviceId":..}],欄位名稱跟 Windows 端一致。 */
        internal fun parseTrustedDevices(json: String?): List<TrustedDevice> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.getJSONObject(i)
                    val fingerprint = obj.optString("Fingerprint").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TrustedDevice(
                        fingerprint = fingerprint,
                        name = obj.optString("Name"),
                        deviceId = if (obj.isNull("DeviceId")) null else obj.optString("DeviceId").takeIf { it.isNotBlank() },
                    )
                }
            }.getOrDefault(emptyList())
        }

        internal fun encodeTrustedDevices(devices: List<TrustedDevice>): String {
            val array = JSONArray()
            devices.forEach { device ->
                array.put(
                    JSONObject().apply {
                        put("Fingerprint", device.fingerprint)
                        put("Name", device.name)
                        put("DeviceId", device.deviceId ?: JSONObject.NULL)
                    }
                )
            }
            return array.toString()
        }
    }

    /**
     * 這台裝置的穩定識別碼(第一次呼叫時產生後就固定不變),交握時送給對方、也放在 mDNS TXT 記錄裡。
     * 裝置名稱可以改、也可能重複,同一台裝置重新連線時要靠這個認出來(對應 Windows 端 DeviceSettingsService.DeviceId)。
     */
    suspend fun getOrCreateDeviceId(): String {
        context.dataStore.data.first()[Keys.DEVICE_ID]?.let { return it }
        var id = ""
        context.dataStore.edit { prefs ->
            id = prefs[Keys.DEVICE_ID] ?: UUID.randomUUID().toString().replace("-", "").also { prefs[Keys.DEVICE_ID] = it }
        }
        return id
    }
}
