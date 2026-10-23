package com.connectit.android.screen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 螢幕鏡像的聲音格式:16-bit 立體聲,取樣率跟 Windows 端預設的混音格式一樣(48kHz)。 */
object ScreenAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = CHANNELS * 2

    /** 只有 Android 10+ 才能擷取其他 App 播放的聲音(AudioPlaybackCapture)。 */
    val captureSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isSilent(buffer: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (buffer[i].toInt() != 0) return false
        return true
    }
}

/**
 * 螢幕鏡像分享端的聲音:用 AudioPlaybackCapture 擷取「其他 App 正在播放的聲音」(影片、音樂、遊戲),
 * 跟畫面共用同一個 MediaProjection。不會錄到麥克風。
 *
 * 限制(系統規定):只擷取得到允許被擷取的聲音——用途是媒體/遊戲/未知的聲音,而且 App 本身沒有
 * 設定拒絕擷取(部分串流 App 為了版權保護會拒絕,這時對方聽不到那個 App 的聲音)。
 * 每 20ms 讀一段,全 0 的片段不送。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ScreenAudioCapturer(
    projection: MediaProjection,
    private val onData: (ByteArray, Int) -> Unit,
) {
    private val record: AudioRecord
    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(ScreenAudioFormat.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(ScreenAudioFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        record = buildRecord(config, format, maxOf(minBuffer, CHUNK_BYTES * 4))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord 初始化失敗")
        }
    }

    // 呼叫端(ScreenMirrorController)建立前已經確認過 RECORD_AUDIO 權限;沒有權限時 build() 會丟 SecurityException。
    @SuppressLint("MissingPermission")
    private fun buildRecord(config: AudioPlaybackCaptureConfiguration, format: AudioFormat, bufferBytes: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()

    fun start() {
        record.startRecording()
        running = true
        thread = Thread({
            val buffer = ByteArray(CHUNK_BYTES)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) break
                    continue
                }
                if (!ScreenAudioFormat.isSilent(buffer, read)) onData(buffer, read)
            }
        }, "ConnectIt-ScreenAudioCapture").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { record.stop() }
        thread?.join(500)
        thread = null
        record.release()
    }

    private companion object {
        /** 20ms 的 16-bit 立體聲。 */
        const val CHUNK_BYTES = ScreenAudioFormat.SAMPLE_RATE / 50 * ScreenAudioFormat.BYTES_PER_FRAME
    }
}

/**
 * 螢幕鏡像觀看端播放對方的聲音(16-bit PCM)。網路送來的片段先排進佇列,由專用執行緒寫進
 * AudioTrack;佇列累積超過 [MAX_QUEUED_MS](兩台裝置時脈的微小差異、或網路卡一下之後一次湧入)
 * 就丟掉新來的片段,聲音不會越拖越慢、跟畫面越差越多。佇列空了 AudioTrack 自己會補靜音。
 */
class ScreenAudioPlayer(val sampleRate: Int, val channels: Int) {
    private val track: AudioTrack
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicInteger(0)
    private val bytesPerMs = sampleRate * channels * 2 / 1000
    @Volatile private var running = true
    private val thread: Thread

    init {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, bytesPerMs * 60))
            .build()
        track.play()
        thread = Thread({
            while (running) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                queuedBytes.addAndGet(-chunk.size)
                track.write(chunk, 0, chunk.size)
            }
        }, "ConnectIt-ScreenAudioPlayer").apply { start() }
    }

    var muted: Boolean = false
        set(value) {
            field = value
            track.setVolume(if (value) 0f else 1f)
        }

    fun enqueue(pcm: ByteArray) {
        if (queuedBytes.get() > MAX_QUEUED_MS * bytesPerMs) return
        queuedBytes.addAndGet(pcm.size)
        queue.offer(pcm)
    }

    fun release() {
        running = false
        thread.join(500)
        runCatching { track.stop() }
        track.release()
    }

    private companion object {
        const val MAX_QUEUED_MS = 200
    }
}
