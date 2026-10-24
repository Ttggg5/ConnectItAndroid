package com.connectit.android.screen

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.connectit.android.net.RemoteKeys

/**
 * 在這台裝置上送出媒體鍵與調整音量(遙控器、或螢幕鏡像中對方按了媒體鍵)。用 [AudioManager] 送出,
 * 跟使用者按耳機線控/實體音量鍵的效果一樣,不需要無障礙服務。
 */
class MediaKeyDispatcher(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 送出 [key](見 [RemoteKeys]),不是媒體鍵/音量鍵就回傳 false。 */
    fun dispatch(key: String?): Boolean {
        when (key) {
            RemoteKeys.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            RemoteKeys.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            RemoteKeys.VOLUME_MUTE -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            RemoteKeys.PLAY_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            RemoteKeys.NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            RemoteKeys.PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            RemoteKeys.STOP -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
            else -> return false
        }
        return true
    }

    private fun adjustVolume(direction: Int) {
        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
    }

    private fun sendMediaKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        runCatching {
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }
}
