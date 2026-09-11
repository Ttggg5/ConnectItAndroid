package com.connectit.android.repo

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AppThemeMode { LIGHT, DARK, AUTO }

data class AppSettings(
    val deviceName: String,
    val themeMode: AppThemeMode,
)

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
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            deviceName = prefs[Keys.DEVICE_NAME] ?: defaultDeviceName(),
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() } ?: AppThemeMode.AUTO,
        )
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[Keys.DEVICE_NAME] = name.ifBlank { defaultDeviceName() } }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    fun defaultDeviceName(): String = Build.MODEL ?: "Android"
}
