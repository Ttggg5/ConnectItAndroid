package com.connectit.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {

    // 名稱必須跟 Windows 端 RemoteKeys 完全一致(網頁 KeyboardEvent.key 的標準命名)。
    @Test
    fun keyNames_matchWindows() {
        assertEquals("MediaPlayPause", RemoteKeys.PLAY_PAUSE)
        assertEquals("MediaTrackNext", RemoteKeys.NEXT)
        assertEquals("MediaTrackPrevious", RemoteKeys.PREVIOUS)
        assertEquals("MediaStop", RemoteKeys.STOP)
        assertEquals("AudioVolumeUp", RemoteKeys.VOLUME_UP)
        assertEquals("AudioVolumeDown", RemoteKeys.VOLUME_DOWN)
        assertEquals("AudioVolumeMute", RemoteKeys.VOLUME_MUTE)
    }

    @Test
    fun isMediaKey_onlyForMediaAndVolume() {
        assertTrue(RemoteKeys.isMediaKey(RemoteKeys.PLAY_PAUSE))
        assertTrue(RemoteKeys.isMediaKey(RemoteKeys.VOLUME_MUTE))
        assertFalse(RemoteKeys.isMediaKey("Enter"))
        assertFalse(RemoteKeys.isMediaKey(null))
    }

    @Test
    fun remoteAccept_roundTripsPlatform() {
        val accept = ScreenMessage(ScreenMessage.REMOTE_ACCEPT, sessionId = "s", platform = "android")
        assertEquals(accept, ScreenMessage.parse(accept.toJson()))
    }
}
