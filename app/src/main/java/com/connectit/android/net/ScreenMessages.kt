package com.connectit.android.net

import org.json.JSONObject

/**
 * 螢幕鏡像的控制訊息(走 [FrameType.SCREEN_CONTROL] 訊框),對應 Windows 端 ScreenMessage.cs。
 * 跟 [FileControlMessage] 一樣,JSON key 必須是跟 Windows 端逐字一致的 PascalCase。
 *
 * 角色:「分享端」(sharer)是畫面被擷取、也可能被遠端操作的那一台;「觀看端」(viewer)是看畫面、
 * 送出操作事件的那一台。任一方都可以發起:
 *
 *   觀看端想看:viewer --screen-request--> sharer,sharer 的使用者同意後開始擷取並回 screen-info
 *              (等同接受),不同意回 screen-reject。
 *   分享端想分享:sharer --screen-offer--> viewer,viewer 回 screen-accept 後 sharer 開始擷取並送
 *              screen-info;不要的話回 screen-reject。
 *
 * 進行中:sharer 送 [FrameType.SCREEN_FRAME](JPEG)給 viewer,viewer 每解完一張回 screen-ack,
 * sharer 最多只允許 [MAX_FRAMES_IN_FLIGHT] 張還沒被確認,避免畫面在 TCP 緩衝區裡越積越多、延遲越來越大。
 * 畫面尺寸改變(旋轉)或是否允許操控改變時 sharer 會再送一次 screen-info。允許操控時 viewer 可以送
 * screen-input。任一方送 screen-stop 結束。同一時間只會有一個螢幕鏡像工作階段。
 *
 * 聲音(選用):sharer 在 screen-info 帶上 [audioSampleRate]/[audioChannels] 代表有分享聲音,之後送
 * [FrameType.SCREEN_AUDIO](16-bit little-endian PCM,交錯排列);不帶代表沒有分享聲音。聲音不受畫面的
 * ack 流量控制,沒聲音(全部是 0)的片段不送。
 *
 * 舊版(不支援螢幕鏡像)收到不認得的訊框型別會直接忽略,所以發起端要自己逾時(見 ScreenMirrorController)。
 */
data class ScreenMessage(
    val type: String,
    val sessionId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** "android" | "windows",觀看端依此決定要顯示哪些按鈕(例如 Android 的返回/主畫面/最近使用)。 */
    val platform: String? = null,
    val allowControl: Boolean? = null,
    val seq: Int? = null,
    /** screen-input 用:見 [ScreenInputAction]。 */
    val action: String? = null,
    /** 0~1 的相對座標(以畫面寬高為基準),兩端解析度不同也不用換算。 */
    val x: Double? = null,
    val y: Double? = null,
    /** 0 = 左鍵(觸控), 1 = 右鍵, 2 = 中鍵。 */
    val button: Int? = null,
    /** 滾輪格數,正值 = 往上捲(對應 WPF MouseWheelEventArgs.Delta > 0),1.0 = 一格(120)。 */
    val deltaY: Double? = null,
    /** 按鍵名稱,採用網頁 KeyboardEvent.key 的命名("Enter"、"Backspace"、"ArrowLeft"、"a"…);nav 動作則是 "back"/"home"/"recents"。 */
    val key: String? = null,
    val text: String? = null,
    /** screen-reject / screen-stop 的原因:見 [ScreenRejectReason]。 */
    val reason: String? = null,
    /** screen-info:分享端有分享聲音時的取樣率(例如 48000),沒有分享聲音時為 null。 */
    val audioSampleRate: Int? = null,
    /** screen-info:聲音的聲道數(目前一律是 2)。 */
    val audioChannels: Int? = null,
) {
    fun toJson(): ByteArray {
        val obj = JSONObject()
        obj.put("Type", type)
        if (sessionId != null) obj.put("SessionId", sessionId)
        if (width != null) obj.put("Width", width)
        if (height != null) obj.put("Height", height)
        if (platform != null) obj.put("Platform", platform)
        if (allowControl != null) obj.put("AllowControl", allowControl)
        if (seq != null) obj.put("Seq", seq)
        if (action != null) obj.put("Action", action)
        if (x != null) obj.put("X", x)
        if (y != null) obj.put("Y", y)
        if (button != null) obj.put("Button", button)
        if (deltaY != null) obj.put("DeltaY", deltaY)
        if (key != null) obj.put("Key", key)
        if (text != null) obj.put("Text", text)
        if (reason != null) obj.put("Reason", reason)
        if (audioSampleRate != null) obj.put("AudioSampleRate", audioSampleRate)
        if (audioChannels != null) obj.put("AudioChannels", audioChannels)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val REQUEST = "screen-request"
        const val OFFER = "screen-offer"
        const val ACCEPT = "screen-accept"
        const val REJECT = "screen-reject"
        const val INFO = "screen-info"
        const val ACK = "screen-ack"
        const val INPUT = "screen-input"
        const val STOP = "screen-stop"

        // 遙控器(不用開螢幕鏡像,只送按鍵,見 RemoteControlController),對應 Windows 端 ScreenMessage.Remote*:
        // 控制端送 remote-request,受控端同意回 remote-accept(帶 Platform),不同意回 remote-reject;之後控制端用
        // screen-input(key-down/key-up,SessionId 是遙控工作階段的)送按鍵,任一方送 remote-stop 結束。
        const val REMOTE_REQUEST = "remote-request"
        const val REMOTE_ACCEPT = "remote-accept"
        const val REMOTE_REJECT = "remote-reject"
        const val REMOTE_STOP = "remote-stop"

        fun parse(bytes: ByteArray): ScreenMessage? = runCatching {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            ScreenMessage(
                type = obj.getString("Type"),
                sessionId = obj.optStringOrNull("SessionId"),
                width = obj.optIntOrNull("Width"),
                height = obj.optIntOrNull("Height"),
                platform = obj.optStringOrNull("Platform"),
                allowControl = if (obj.has("AllowControl") && !obj.isNull("AllowControl")) obj.getBoolean("AllowControl") else null,
                seq = obj.optIntOrNull("Seq"),
                action = obj.optStringOrNull("Action"),
                x = obj.optDoubleOrNull("X"),
                y = obj.optDoubleOrNull("Y"),
                button = obj.optIntOrNull("Button"),
                deltaY = obj.optDoubleOrNull("DeltaY"),
                key = obj.optStringOrNull("Key"),
                text = obj.optStringOrNull("Text"),
                reason = obj.optStringOrNull("Reason"),
                audioSampleRate = obj.optIntOrNull("AudioSampleRate"),
                audioChannels = obj.optIntOrNull("AudioChannels"),
            )
        }.getOrNull()
    }
}

object ScreenInputAction {
    const val POINTER_DOWN = "pointer-down"
    const val POINTER_MOVE = "pointer-move"
    const val POINTER_UP = "pointer-up"
    const val WHEEL = "wheel"
    const val KEY_DOWN = "key-down"
    const val KEY_UP = "key-up"
    const val TEXT = "text"
    const val NAV = "nav"
}

/**
 * 遙控器與螢幕鏡像快捷鍵列用到的按鍵名稱(網頁 KeyboardEvent.key 的標準命名),對應 Windows 端 RemoteKeys。
 * Android 受控端只支援這些媒體鍵與音量(用 AudioManager 送出,不需要無障礙服務)。
 */
object RemoteKeys {
    const val PLAY_PAUSE = "MediaPlayPause"
    const val NEXT = "MediaTrackNext"
    const val PREVIOUS = "MediaTrackPrevious"
    const val STOP = "MediaStop"
    const val VOLUME_UP = "AudioVolumeUp"
    const val VOLUME_DOWN = "AudioVolumeDown"
    const val VOLUME_MUTE = "AudioVolumeMute"

    fun isMediaKey(key: String?): Boolean = key in setOf(PLAY_PAUSE, NEXT, PREVIOUS, STOP, VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE)
}

object ScreenRejectReason {
    /** 使用者按了拒絕。 */
    const val DECLINED = "declined"
    /** 已經有進行中的螢幕鏡像。 */
    const val BUSY = "busy"
    /** 這台裝置不支援擷取螢幕。 */
    const val UNSUPPORTED = "unsupported"
    /** 使用者在系統的「開始錄製或投放」確認畫面按了取消(Android)。 */
    const val PERMISSION_DENIED = "permission-denied"
}

/** 同時允許幾張畫面還沒收到 screen-ack,見 [ScreenMessage] 的說明。 */
const val MAX_FRAMES_IN_FLIGHT = 4

/** 畫面訊框:[4 bytes big-endian 序號][JPEG]。 */
const val SCREEN_FRAME_HEADER_SIZE = 4

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null
