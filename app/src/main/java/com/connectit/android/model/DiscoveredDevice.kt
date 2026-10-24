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
    /** TXT 記錄的 "id":對方的穩定裝置識別碼(舊版沒有)。 */
    val deviceId: String? = null,
    /** TXT 記錄的 "v":對方支援的協定版本。null = 讀不到 TXT(不知道);1 = 不支援加密連線的舊版。 */
    val protocolVersion: Int? = null,
) {
    /** 確定對方是舊版(不支援加密連線),連不上,要請對方更新。 */
    val needsUpdate: Boolean
        get() = protocolVersion != null && protocolVersion < com.connectit.android.connection.ConnectionEngine.PROTOCOL_VERSION

    val key: String get() = instanceName

    val displayName: String
        get() = friendlyName?.takeIf { it.isNotBlank() } ?: instanceName.substringBefore('.')
}
