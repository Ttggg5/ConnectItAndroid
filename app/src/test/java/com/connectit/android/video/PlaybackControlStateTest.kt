package com.connectit.android.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackControlState] 是遠端控制模式的「碼表」外插邏輯,對應 Windows 端
 * Services/PlaybackControlState.cs——這裡用可注入的假時鐘(而不是 [android.os.SystemClock])
 * 驗證同一套邏輯,不需要 Robolectric/實機。
 */
class PlaybackControlStateTest {

    private class FakeClock(var nowMs: Long = 0L) {
        fun read(): Long = nowMs
    }

    /** 純 JVM 單元測試裡不能呼叫真的 [android.os.SystemClock](未 mock 會直接丟例外),所有測試都要
     * 明確注入時鐘,即使不在乎時間經過也一樣(建構子本身就會呼叫一次 clockMs() 記錄初始時間)。 */
    private fun newState(clockMs: () -> Long = { 0L }) = PlaybackControlState(clockMs)

    @Test
    fun `initial snapshot is disabled with no video`() {
        val state = newState()

        val snapshot = state.snapshot()

        assertFalse(snapshot.enabled)
        assertNull(snapshot.videoRelativePath)
        assertFalse(snapshot.isPlaying)
        assertEquals(0L, snapshot.positionMs)
        assertEquals(1.0, snapshot.playbackRate, 0.0)
        assertEquals(1.0, snapshot.volume, 0.0)
        assertFalse(snapshot.muted)
    }

    @Test
    fun `setPlaybackRate clamps to supported range`() {
        val state = newState()

        state.setPlaybackRate(10.0)
        assertEquals(4.0, state.snapshot().playbackRate, 0.0)

        state.setPlaybackRate(0.01)
        assertEquals(0.25, state.snapshot().playbackRate, 0.0)
    }

    @Test
    fun `setVolume clamps to zero one range`() {
        val state = newState()

        state.setVolume(1.5)
        assertEquals(1.0, state.snapshot().volume, 0.0)

        state.setVolume(-0.5)
        assertEquals(0.0, state.snapshot().volume, 0.0)
    }

    @Test
    fun `setMuted toggles independently of volume`() {
        val state = newState()

        state.setMuted(true)
        val snapshot = state.snapshot()
        assertTrue(snapshot.muted)
        assertEquals(1.0, snapshot.volume, 0.0)
    }

    @Test
    fun `reset restores playback rate volume and muted to defaults`() {
        val state = newState()
        state.setPlaybackRate(2.0)
        state.setVolume(0.3)
        state.setMuted(true)

        state.reset()

        val snapshot = state.snapshot()
        assertEquals(1.0, snapshot.playbackRate, 0.0)
        assertEquals(1.0, snapshot.volume, 0.0)
        assertFalse(snapshot.muted)
    }

    @Test
    fun `setVideo defaults to autoplay from given position`() {
        val state = newState()

        state.setVideo("movies/a.mp4", startPositionMs = 5_000)

        val snapshot = state.snapshot()
        assertEquals("movies/a.mp4", snapshot.videoRelativePath)
        assertTrue(snapshot.isPlaying)
        assertEquals(5_000L, snapshot.positionMs)
    }

    @Test
    fun `snapshot while playing extrapolates position by elapsed time`() {
        val clock = FakeClock()
        val state = newState(clock::read)

        state.setVideo("movies/a.mp4", startPositionMs = 1_000)
        clock.nowMs = 3_500

        assertEquals(1_000L + 3_500L, state.snapshot().positionMs)
    }

    @Test
    fun `snapshot while paused does not advance position`() {
        val clock = FakeClock()
        val state = newState(clock::read)

        state.setVideo("movies/a.mp4", startPositionMs = 1_000, autoplay = false)
        clock.nowMs = 10_000

        assertEquals(1_000L, state.snapshot().positionMs)
    }

    @Test
    fun `setPlaying false freezes position at current extrapolated value`() {
        val clock = FakeClock()
        val state = newState(clock::read)

        state.setVideo("movies/a.mp4", startPositionMs = 0)
        clock.nowMs = 2_000
        state.setPlaying(false)
        clock.nowMs = 10_000

        assertEquals(2_000L, state.snapshot().positionMs)
    }

    @Test
    fun `setPlaying true resumes extrapolating from frozen position`() {
        val clock = FakeClock()
        val state = newState(clock::read)

        state.setVideo("movies/a.mp4", startPositionMs = 0)
        clock.nowMs = 2_000
        state.setPlaying(false)
        clock.nowMs = 5_000
        state.setPlaying(true)
        clock.nowMs = 6_000

        assertEquals(2_000L + 1_000L, state.snapshot().positionMs)
    }

    @Test
    fun `seek sets position regardless of playing state`() {
        val state = newState()

        state.setVideo("movies/a.mp4", startPositionMs = 0)
        state.seek(42_000)

        assertEquals(42_000L, state.snapshot().positionMs)
    }

    @Test
    fun `seek negative value clamps to zero`() {
        val state = newState()

        state.seek(-500)

        assertEquals(0L, state.snapshot().positionMs)
    }

    @Test
    fun `setEnabled no-op when already at that value does not bump version`() {
        val state = newState()
        state.setEnabled(true)
        val versionAfterFirstSet = state.snapshot().version

        state.setEnabled(true)

        assertEquals(versionAfterFirstSet, state.snapshot().version)
    }

    @Test
    fun `version increments on every mutation`() {
        val state = newState()
        val v0 = state.snapshot().version

        state.setEnabled(true)
        val v1 = state.snapshot().version
        state.setVideo("a.mp4")
        val v2 = state.snapshot().version
        state.setPlaying(false)
        val v3 = state.snapshot().version
        state.seek(1000)
        val v4 = state.snapshot().version

        assertTrue(v1 > v0)
        assertTrue(v2 > v1)
        assertTrue(v3 > v2)
        assertTrue(v4 > v3)
    }

    @Test
    fun `reset clears enabled video and playing state`() {
        val state = newState()
        state.setEnabled(true)
        state.setVideo("a.mp4", startPositionMs = 1_000)

        state.reset()

        val snapshot = state.snapshot()
        assertFalse(snapshot.enabled)
        assertNull(snapshot.videoRelativePath)
        assertFalse(snapshot.isPlaying)
        assertEquals(0L, snapshot.positionMs)
    }

    @Test
    fun `toJson uses PascalCase keys matching the Windows wire format`() {
        val state = newState()
        state.setVideo("movies/a.mp4", startPositionMs = 1_500)

        val json = state.snapshot().toJson().toString()

        assertTrue(json.contains("\"Enabled\""))
        assertTrue(json.contains("\"VideoRelativePath\":\"movies/a.mp4\""))
        assertTrue(json.contains("\"IsPlaying\""))
        assertTrue(json.contains("\"PositionMs\":1500"))
        assertTrue(json.contains("\"Version\""))
        assertTrue(json.contains("\"UpdatedAtUtcMs\""))
    }

    @Test
    fun `versionChanges tracks snapshot version on every change`() {
        val state = newState()
        assertEquals(state.snapshot().version, state.versionChanges.value)

        state.setEnabled(true)
        state.setVideo("a.mp4")
        state.seek(10_000)
        state.setPlaying(false)

        assertEquals(4L, state.versionChanges.value)
        assertEquals(state.snapshot().version, state.versionChanges.value)
    }

    @Test
    fun `versionChanges does not change when a setter is a no-op`() {
        val state = newState()
        state.setMuted(true)
        val before = state.versionChanges.value

        state.setMuted(true)
        state.setEnabled(false)

        assertEquals(before, state.versionChanges.value)
    }
}
