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
import kotlinx.coroutines.flow.map

enum class AppThemeMode { LIGHT, DARK, AUTO }

data class AppSettings(
    val deviceName: String,
    val themeMode: AppThemeMode,
    /** 收到傳輸/斷線等事件時是否跳出系統通知(連線請求對話框不受影響,只影響提醒用的通知)。 */
    val notificationsEnabled: Boolean = true,
    /** 已標記為信任的裝置名稱(比對 [com.connectit.android.model.ConnectionRequest.requesterName]),
     * 收到這些裝置的連線請求時不再跳出確認對話框,直接自動接受。 */
    val trustedDeviceNames: Set<String> = emptySet(),
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
    val videoAutoplayCountdownSeconds: Int = VideoServerPlaybackOptions.DEFAULT_AUTOPLAY_COUNTDOWN_SECONDS,
    val videoDefaultVolumePercent: Int = VideoServerPlaybackOptions.DEFAULT_VOLUME_PERCENT,
    val videoDefaultSpeed: Double = VideoServerPlaybackOptions.DEFAULT_PLAYBACK_SPEED,
    /** 使用者額外加入的副檔名(內建清單以外),已正規化成小寫、不含開頭 '.'、不重複。 */
    val videoExtraExtensions: List<String> = emptyList(),
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
        val TRUSTED_DEVICE_NAMES = stringSetPreferencesKey("trusted_device_names")
        val CUSTOM_DOWNLOAD_FOLDER_URI = stringPreferencesKey("custom_download_folder_uri")
        val CONNECT_TIMEOUT_SECONDS = intPreferencesKey("connect_timeout_seconds")
        val PREFERRED_PORT = intPreferencesKey("preferred_port")
        val VIDEO_SHARE_FOLDER_URI = stringPreferencesKey("video_share_folder_uri")
        val VIDEO_DEFAULT_SORT = stringPreferencesKey("video_default_sort")
        val VIDEO_AUTOPLAY_NEXT = booleanPreferencesKey("video_autoplay_next")
        val VIDEO_SHUFFLE = booleanPreferencesKey("video_shuffle")
        val VIDEO_AUTOPLAY_COUNTDOWN_SECONDS = intPreferencesKey("video_autoplay_countdown_seconds")
        val VIDEO_DEFAULT_VOLUME_PERCENT = intPreferencesKey("video_default_volume_percent")
        val VIDEO_DEFAULT_SPEED = doublePreferencesKey("video_default_speed")
        val VIDEO_EXTRA_EXTENSIONS = stringPreferencesKey("video_extra_extensions")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            deviceName = prefs[Keys.DEVICE_NAME] ?: defaultDeviceName(),
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() } ?: AppThemeMode.AUTO,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            trustedDeviceNames = prefs[Keys.TRUSTED_DEVICE_NAMES] ?: emptySet(),
            customDownloadFolderUri = prefs[Keys.CUSTOM_DOWNLOAD_FOLDER_URI],
            connectTimeoutSeconds = (prefs[Keys.CONNECT_TIMEOUT_SECONDS] ?: AppSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS)
                .coerceIn(AppSettings.MIN_CONNECT_TIMEOUT_SECONDS, AppSettings.MAX_CONNECT_TIMEOUT_SECONDS),
            preferredPort = prefs[Keys.PREFERRED_PORT] ?: 0,
            videoShareFolderUri = prefs[Keys.VIDEO_SHARE_FOLDER_URI],
            videoDefaultSort = prefs[Keys.VIDEO_DEFAULT_SORT]?.takeIf { sort -> VideoSort.entries.any { it.value == sort } }
                ?: VideoSort.DEFAULT_VALUE,
            videoAutoplayNext = prefs[Keys.VIDEO_AUTOPLAY_NEXT] ?: VideoServerPlaybackOptions.DEFAULT_AUTOPLAY_NEXT,
            videoShuffle = prefs[Keys.VIDEO_SHUFFLE] ?: VideoServerPlaybackOptions.DEFAULT_SHUFFLE,
            videoAutoplayCountdownSeconds = (prefs[Keys.VIDEO_AUTOPLAY_COUNTDOWN_SECONDS]
                ?: VideoServerPlaybackOptions.DEFAULT_AUTOPLAY_COUNTDOWN_SECONDS)
                .coerceIn(VideoServerPlaybackOptions.MIN_AUTOPLAY_COUNTDOWN_SECONDS, VideoServerPlaybackOptions.MAX_AUTOPLAY_COUNTDOWN_SECONDS),
            videoDefaultVolumePercent = (prefs[Keys.VIDEO_DEFAULT_VOLUME_PERCENT] ?: VideoServerPlaybackOptions.DEFAULT_VOLUME_PERCENT)
                .coerceIn(0, 100),
            videoDefaultSpeed = prefs[Keys.VIDEO_DEFAULT_SPEED]?.takeIf { VideoServerPlaybackOptions.VALID_PLAYBACK_SPEEDS.contains(it) }
                ?: VideoServerPlaybackOptions.DEFAULT_PLAYBACK_SPEED,
            videoExtraExtensions = VideoLibraryScanner.parseExtraExtensions(prefs[Keys.VIDEO_EXTRA_EXTENSIONS] ?: ""),
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

    suspend fun trustDevice(name: String) {
        if (name.isBlank()) return
        context.dataStore.edit { it[Keys.TRUSTED_DEVICE_NAMES] = (it[Keys.TRUSTED_DEVICE_NAMES] ?: emptySet()) + name }
    }

    suspend fun untrustDevice(name: String) {
        context.dataStore.edit { it[Keys.TRUSTED_DEVICE_NAMES] = (it[Keys.TRUSTED_DEVICE_NAMES] ?: emptySet()) - name }
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

    suspend fun setVideoAutoplayCountdownSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(
            VideoServerPlaybackOptions.MIN_AUTOPLAY_COUNTDOWN_SECONDS, VideoServerPlaybackOptions.MAX_AUTOPLAY_COUNTDOWN_SECONDS,
        )
        context.dataStore.edit { it[Keys.VIDEO_AUTOPLAY_COUNTDOWN_SECONDS] = clamped }
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

    fun defaultDeviceName(): String = Build.MODEL ?: "Android"
}
