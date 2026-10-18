package com.connectit.android.repo

import com.connectit.android.video.VideoServerPlaybackOptions

/**
 * 設定頁輸入框的即時驗證(對應 Windows 端 Services/SettingsValidation.cs):輸入當下就判斷是否合法,
 * 合法才自動儲存,不合法就在輸入框下方顯示原因——取代以前每個欄位都要另外按「儲存」、
 * 而且超出範圍會被默默夾住的做法。
 */
object SettingsValidation {
    const val MAX_DEVICE_NAME_LENGTH = 40

    data class Result(val isValid: Boolean, val value: Int = 0, val error: String? = null) {
        companion object {
            fun ok(value: Int) = Result(true, value)
            fun fail(error: String) = Result(false, error = error)
        }
    }

    fun validateConnectTimeout(text: String): Result =
        validateRange(text, AppSettings.MIN_CONNECT_TIMEOUT_SECONDS, AppSettings.MAX_CONNECT_TIMEOUT_SECONDS)

    fun validateAutoplayCountdown(text: String): Result = validateRange(
        text,
        VideoServerPlaybackOptions.MIN_AUTOPLAY_COUNTDOWN_SECONDS,
        VideoServerPlaybackOptions.MAX_AUTOPLAY_COUNTDOWN_SECONDS,
    )

    /** 留空代表交由系統自動指派(回傳 0);否則必須是 1024-65535(避開需要特殊權限的特權連接埠)。 */
    fun validatePort(text: String): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.ok(0)
        val port = trimmed.toIntOrNull()
        return if (port != null && port in 1024..65535) {
            Result.ok(port)
        } else {
            Result.fail("請輸入 1024–65535 之間的連接埠,或留空讓系統自動指派")
        }
    }

    /** 裝置名稱不能是空白(其他裝置會看不到名字),長度上限 [MAX_DEVICE_NAME_LENGTH]。 */
    fun validateDeviceName(text: String): String? = when {
        text.isBlank() -> "請輸入裝置名稱"
        text.trim().length > MAX_DEVICE_NAME_LENGTH -> "名稱最多 $MAX_DEVICE_NAME_LENGTH 個字"
        else -> null
    }

    private fun validateRange(text: String, min: Int, max: Int): Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.fail("請輸入 $min–$max 秒")
        val value = trimmed.toIntOrNull()
        return if (value != null && value in min..max) Result.ok(value) else Result.fail("請輸入 $min–$max 之間的數字(秒)")
    }
}
