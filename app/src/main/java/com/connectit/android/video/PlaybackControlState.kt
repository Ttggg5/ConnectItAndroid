package com.connectit.android.video

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 遠端控制模式的播放狀態,對應 Windows 端 Services/PlaybackControlState.cs——用「碼表」模型
 * 記錄上次更新時的位置、當下是否在播放、跟一個 monotonic 時間戳,每次被問到時用經過的時間做
 * 外插算出「現在」的位置,觀眾端(主機透過 `/control/events` 推送,或舊版的 `/control/state`
 * 輪詢)不需要自己處理時鐘校正。
 *
 * 主機端不一定真的在播放影片(遙控器介面可以只是選片/下指令),所以這裡完全不持有/依賴任何
 * ExoPlayer 實例,純粹是一個被動的資料模型;由 [com.connectit.android.service.ConnectItService]
 * 持有單一實例,同時餵給 [VideoHostServer](讀,回應輪詢)跟遙控 UI(寫,下指令)。
 *
 * [clockMs] 預設用 [SystemClock.elapsedRealtime](不受系統時間校正/時區影響),測試時可注入假時鐘。
 */
class PlaybackControlState(private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }) {

    data class Snapshot(
        val enabled: Boolean,
        val videoRelativePath: String?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val playbackRate: Double,
        val volume: Double,
        val muted: Boolean,
        val version: Long,
        val updatedAtUtcMs: Long,
    )

    private val gate = Object()
    private var enabled = false
    private var videoRelativePath: String? = null
    private var isPlaying = false
    private var positionAtUpdate = 0L
    private var updatedAt = clockMs()
    private var updatedAtUtcMs = System.currentTimeMillis()
    private var playbackRate = 1.0
    private var volume = 1.0
    private var muted = false
    private var version = 0L

    private val _versionChanges = MutableStateFlow(0L)

    /** 目前的 [Snapshot.version],每次狀態變更都會更新——`/control/events` 靠這個等待「下一次變更」,
     * 一變更就把新狀態推送給觀眾端,不用觀眾端自己輪詢。 */
    val versionChanges: StateFlow<Long> = _versionChanges.asStateFlow()

    /** 呼叫端必須已持有 [gate]。 */
    private fun bumpVersionLocked() {
        version++
        _versionChanges.value = version
    }

    fun setEnabled(value: Boolean) {
        synchronized(gate) {
            if (enabled == value) return
            enabled = value
            bumpVersionLocked()
        }
    }

    /** 切換主機目前播放的影片。[autoplay] 預設 true,符合「換片就接著播」的直覺行為。 */
    fun setVideo(relativePath: String, startPositionMs: Long = 0, autoplay: Boolean = true) {
        synchronized(gate) {
            videoRelativePath = relativePath
            positionAtUpdate = startPositionMs.coerceAtLeast(0)
            updatedAt = clockMs()
            updatedAtUtcMs = System.currentTimeMillis()
            isPlaying = autoplay
            bumpVersionLocked()
        }
    }

    fun setPlaying(playing: Boolean) {
        synchronized(gate) {
            if (isPlaying == playing) return
            // 切換播放狀態前,先把目前(外插後)的位置定住,否則從 playing 切成 paused 那一瞬間,
            // 下一次 snapshot 會少算/多算這段時間。
            positionAtUpdate = currentPositionLocked()
            updatedAt = clockMs()
            updatedAtUtcMs = System.currentTimeMillis()
            isPlaying = playing
            bumpVersionLocked()
        }
    }

    fun seek(positionMs: Long) {
        synchronized(gate) {
            positionAtUpdate = positionMs.coerceAtLeast(0)
            updatedAt = clockMs()
            updatedAtUtcMs = System.currentTimeMillis()
            bumpVersionLocked()
        }
    }

    fun setPlaybackRate(rate: Double) {
        synchronized(gate) {
            val clamped = rate.coerceIn(0.25, 4.0)
            if (playbackRate == clamped) return
            playbackRate = clamped
            bumpVersionLocked()
        }
    }

    fun setVolume(value: Double) {
        synchronized(gate) {
            val clamped = value.coerceIn(0.0, 1.0)
            if (volume == clamped) return
            volume = clamped
            bumpVersionLocked()
        }
    }

    fun setMuted(value: Boolean) {
        synchronized(gate) {
            if (muted == value) return
            muted = value
            bumpVersionLocked()
        }
    }

    /** 重新分享/停止分享時呼叫——遠端控制狀態不跨 session 保留,一律回到「關閉、沒選片」。 */
    fun reset() {
        synchronized(gate) {
            enabled = false
            videoRelativePath = null
            isPlaying = false
            positionAtUpdate = 0
            updatedAt = clockMs()
            updatedAtUtcMs = System.currentTimeMillis()
            playbackRate = 1.0
            volume = 1.0
            muted = false
            bumpVersionLocked()
        }
    }

    fun snapshot(): Snapshot {
        synchronized(gate) {
            return Snapshot(
                enabled = enabled,
                videoRelativePath = videoRelativePath,
                isPlaying = isPlaying,
                positionMs = currentPositionLocked(),
                playbackRate = playbackRate,
                volume = volume,
                muted = muted,
                version = version,
                updatedAtUtcMs = updatedAtUtcMs,
            )
        }
    }

    /** 呼叫端必須已持有 [gate]。播放中的話用經過的時間外插目前位置,暫停中就是定住的那個值。 */
    private fun currentPositionLocked(): Long =
        if (isPlaying) positionAtUpdate + (clockMs() - updatedAt).coerceAtLeast(0) else positionAtUpdate
}

/** 對應 Windows 端 `/control/state` 的 JSON 形狀,PascalCase 欄位名稱,`writeControlState`(輪詢用)
 * 跟觀看頁 HTML 內嵌的初始 snapshot(避免第一次輪詢前畫面不同步)共用同一份序列化邏輯。 */
fun PlaybackControlState.Snapshot.toJson(): JSONObject = JSONObject()
    .put("Enabled", enabled)
    .put("VideoRelativePath", videoRelativePath ?: JSONObject.NULL)
    .put("IsPlaying", isPlaying)
    .put("PositionMs", positionMs)
    .put("PlaybackRate", playbackRate)
    .put("Volume", volume)
    .put("Muted", muted)
    .put("Version", version)
    .put("UpdatedAtUtcMs", updatedAtUtcMs)
