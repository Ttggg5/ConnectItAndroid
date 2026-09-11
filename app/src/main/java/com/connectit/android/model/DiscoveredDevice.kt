package com.connectit.android.model

/**
 * 透過 mDNS/DNS-SD 在區網內找到的一台裝置(裝置配對或影片伺服器共用同一個模型),
 * 對應 Windows 端的 DiscoveredDevice.cs。
 */
data class DiscoveredDevice(
    /** DNS-SD 服務實體名稱,同時作為清單中去重/比對用的唯一鍵。 */
    val instanceName: String,
    val host: String,
    val port: Int,
    /** 從 TXT 記錄讀到的原始裝置名稱;沒有的話(或 API < 28 讀不到 TXT)則回退用實體名稱。 */
    val friendlyName: String?,
) {
    val key: String get() = instanceName

    val displayName: String
        get() = friendlyName?.takeIf { it.isNotBlank() } ?: instanceName.substringBefore('.')
}
