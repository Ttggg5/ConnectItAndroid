package com.connectit.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRateTrackerTest {
    private var now = 0.0

    private fun tracker(total: Long) = TransferRateTracker { now }.also { it.reset(total) }

    @Test
    fun update_computesPercentSpeedAndRemaining() {
        val t = tracker(10_000_000)
        now = 1.0
        val s = t.update(1_000_000, 10_000_000)

        assertEquals(10, s.percent)
        assertEquals(1_000_000.0, s.bytesPerSecond!!, 0.5)
        assertEquals(9.0, s.remainingSeconds!!, 0.001)
    }

    @Test
    fun update_smoothsSpeed() {
        val t = tracker(100_000_000)
        now = 1.0
        t.update(1_000_000, 100_000_000)
        now = 2.0
        val s = t.update(11_000_000, 100_000_000)

        assertTrue(s.bytesPerSecond!! > 1_000_000 && s.bytesPerSecond!! < 10_000_000)
    }

    @Test
    fun update_withoutEnoughSamples_hasNoSpeed() {
        val t = tracker(1000)
        now = 0.1
        val s = t.update(100, 1000)

        assertNull(s.bytesPerSecond)
        assertEquals("計算速度中…", s.rateText)
    }

    @Test
    fun formatDuration_usesReadableUnits() {
        assertEquals("8 秒", TransferRateTracker.formatDuration(8.0))
        assertEquals("3 分 05 秒", TransferRateTracker.formatDuration(185.0))
        assertEquals("1 小時 02 分", TransferRateTracker.formatDuration(3720.0))
    }
}
