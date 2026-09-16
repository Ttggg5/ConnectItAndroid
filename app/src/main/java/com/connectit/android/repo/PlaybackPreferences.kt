package com.connectit.android.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.videoPlaybackDataStore by preferencesDataStore(name = "connectit-video-playback")

/**
 * 記住播放進度/音量/播放速度/自動播放下一部/隨機播放設定,對應 Windows 觀看頁用 `localStorage` 做的事
 * (見 VideoStreamingService.cs 的 BuildWatchPageScript):音量、速度、自動播放、隨機播放是整台伺服器
 * 共用的全域設定,播放進度則是每一支影片各自記一份。
 */
class PlaybackPreferences(private val context: Context) {

    private object Keys {
        val VOLUME = floatPreferencesKey("volume")
        val SPEED = floatPreferencesKey("speed")
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val SHUFFLE = booleanPreferencesKey("shuffle")
    }

    val volume: Flow<Float> = context.videoPlaybackDataStore.data.map { it[Keys.VOLUME] ?: 1f }
    val speed: Flow<Float> = context.videoPlaybackDataStore.data.map { it[Keys.SPEED] ?: 1f }
    val autoplayNext: Flow<Boolean> = context.videoPlaybackDataStore.data.map { it[Keys.AUTOPLAY_NEXT] ?: true }
    val shuffle: Flow<Boolean> = context.videoPlaybackDataStore.data.map { it[Keys.SHUFFLE] ?: false }

    suspend fun setVolume(value: Float) {
        context.videoPlaybackDataStore.edit { it[Keys.VOLUME] = value.coerceIn(0f, 1f) }
    }

    suspend fun setSpeed(value: Float) {
        context.videoPlaybackDataStore.edit { it[Keys.SPEED] = value }
    }

    suspend fun setAutoplayNext(value: Boolean) {
        context.videoPlaybackDataStore.edit { it[Keys.AUTOPLAY_NEXT] = value }
    }

    suspend fun setShuffle(value: Boolean) {
        context.videoPlaybackDataStore.edit { it[Keys.SHUFFLE] = value }
    }

    /** [videoKey] 建議用「host:port/relativePath」,同一支影片在不同伺服器上分開記錄。 */
    suspend fun savePosition(videoKey: String, positionMs: Long) {
        context.videoPlaybackDataStore.edit { it[positionKey(videoKey)] = positionMs }
    }

    suspend fun getPosition(videoKey: String): Long? =
        context.videoPlaybackDataStore.data.first()[positionKey(videoKey)]

    suspend fun clearPosition(videoKey: String) {
        context.videoPlaybackDataStore.edit { it.remove(positionKey(videoKey)) }
    }

    private fun positionKey(videoKey: String) = longPreferencesKey("pos_${videoKey.hashCode()}")
}
