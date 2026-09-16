package com.connectit.android.video

/**
 * 開始分享影片時套用的自訂選項快照,對應 Windows 端 Services/VideoServerPlaybackOptions.cs——
 * 值來自使用者在設定頁調整過的 [com.connectit.android.repo.SettingsRepository] 設定,
 * 只影響「按下開始分享」當下套用的初始值,不會在伺服器執行中即時生效。
 */
data class VideoServerPlaybackOptions(
    val defaultSort: String = VideoSort.DEFAULT_VALUE,
    val autoplayNext: Boolean = DEFAULT_AUTOPLAY_NEXT,
    val shuffle: Boolean = DEFAULT_SHUFFLE,
    val defaultVolumePercent: Int = DEFAULT_VOLUME_PERCENT,
    val defaultSpeed: Double = DEFAULT_PLAYBACK_SPEED,
    val autoplayCountdownSeconds: Int = DEFAULT_AUTOPLAY_COUNTDOWN_SECONDS,
    val extraExtensions: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_AUTOPLAY_NEXT = true
        const val DEFAULT_SHUFFLE = false
        const val DEFAULT_VOLUME_PERCENT = 100
        const val DEFAULT_PLAYBACK_SPEED = 1.0
        const val DEFAULT_AUTOPLAY_COUNTDOWN_SECONDS = 5
        const val MIN_AUTOPLAY_COUNTDOWN_SECONDS = 1
        const val MAX_AUTOPLAY_COUNTDOWN_SECONDS = 30

        // 跟 Windows 端 VideoServerSettingsService.ValidPlaybackSpeeds 保持一致。
        val VALID_PLAYBACK_SPEEDS = listOf(0.5, 1.0, 1.25, 1.5, 2.0)
    }
}
