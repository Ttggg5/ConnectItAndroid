package com.connectit.android.util

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 根據傳輸進度回報計算百分比、傳輸速度與預計剩餘時間(對應 Windows 端 Services/TransferRateTracker.cs),
 * 給傳輸卡片與進度通知顯示「45%・3.2 MB/s・約剩 12 秒」。速度用指數移動平均平滑,避免數字一直跳。
 * 時間來源可注入,方便單元測試。
 */
class TransferRateTracker(private val nowSeconds: () -> Double = { System.nanoTime() / 1_000_000_000.0 }) {

    private var totalBytes = 0L
    private var lastBytes = 0L
    private var lastSampleAt = nowSeconds()
    private var smoothedBytesPerSecond: Double? = null

    fun reset(totalBytes: Long = 0L) {
        this.totalBytes = max(0L, totalBytes)
        lastBytes = 0L
        lastSampleAt = nowSeconds()
        smoothedBytesPerSecond = null
    }

    fun update(bytesTransferred: Long, totalBytes: Long): Snapshot {
        if (totalBytes > 0) this.totalBytes = totalBytes

        val now = nowSeconds()
        val elapsed = now - lastSampleAt
        if (bytesTransferred < lastBytes) {
            lastBytes = bytesTransferred
            lastSampleAt = now
        } else if (elapsed >= MIN_SAMPLE_INTERVAL_SECONDS) {
            val instant = (bytesTransferred - lastBytes) / elapsed
            smoothedBytesPerSecond = smoothedBytesPerSecond?.let { it + SMOOTHING_FACTOR * (instant - it) } ?: instant
            lastBytes = bytesTransferred
            lastSampleAt = now
        }

        val fraction = if (this.totalBytes > 0) (bytesTransferred.toDouble() / this.totalBytes).coerceIn(0.0, 1.0) else 0.0
        val speed = smoothedBytesPerSecond
        val remainingSeconds = if (speed != null && speed > 1 && this.totalBytes > 0) {
            max(0L, this.totalBytes - bytesTransferred) / speed
        } else {
            null
        }
        return Snapshot(fraction, bytesTransferred, this.totalBytes, speed, remainingSeconds)
    }

    data class Snapshot(
        val fraction: Double,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val bytesPerSecond: Double?,
        val remainingSeconds: Double?,
    ) {
        val percent: Int get() = floor(fraction * 100).toInt()

        /** 「3.2 MB/s・約剩 12 秒」;樣本還不夠時回傳「計算速度中…」。 */
        val rateText: String
            get() {
                val speed = bytesPerSecond ?: return "計算速度中…"
                val base = "${formatBytes(speed.toLong())}/s"
                return remainingSeconds?.let { "$base・約剩 ${formatDuration(it)}" } ?: base
            }
    }

    companion object {
        private const val MIN_SAMPLE_INTERVAL_SECONDS = 0.25
        private const val SMOOTHING_FACTOR = 0.3

        fun formatBytes(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unitIndex = 0
            while (value >= 1024 && unitIndex < units.size - 1) {
                value /= 1024
                unitIndex++
            }
            return if (unitIndex == 0) {
                "${value.toLong()} ${units[unitIndex]}"
            } else {
                String.format(Locale.US, "%.1f %s", value, units[unitIndex]).replace(".0 ", " ")
            }
        }

        /** 「8 秒」「3 分 05 秒」「1 小時 02 分」。 */
        fun formatDuration(seconds: Double): String {
            val total = ceil(seconds).toLong()
            return when {
                total < 60 -> "${max(1L, total)} 秒"
                total < 3600 -> String.format(Locale.US, "%d 分 %02d 秒", total / 60, total % 60)
                else -> String.format(Locale.US, "%d 小時 %02d 分", total / 3600, total % 3600 / 60)
            }
        }
    }
}
