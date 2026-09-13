package com.connectit.android.repo

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    fun defaultDeviceName(): String = Build.MODEL ?: "Android"
}
