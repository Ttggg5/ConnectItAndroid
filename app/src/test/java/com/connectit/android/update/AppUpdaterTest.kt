package com.connectit.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class AppUpdaterTest {

    @Test
    fun parseVersionCode_takesLastSegmentOfTag() {
        assertEquals(42L, AppUpdater.parseVersionCode("v1.0.42"))
        assertEquals(7L, AppUpdater.parseVersionCode(" 1.0.7 "))
        assertNull(AppUpdater.parseVersionCode("latest"))
        assertNull(AppUpdater.parseVersionCode(null))
    }

    @Test
    fun parseRelease_findsApkAsset() {
        val json = """
            {
              "tag_name": "v1.0.42",
              "body": "Fix things",
              "assets": [
                {"name": "other.zip", "browser_download_url": "https://example.com/other.zip", "size": 1},
                {"name": "ConnectIt.apk", "browser_download_url": "https://example.com/ConnectIt.apk",
                 "size": 1234, "digest": "sha256:abcd"}
              ]
            }
        """.trimIndent()

        val release = AppUpdater.parseRelease(json)!!

        assertEquals(42L, release.versionCode)
        assertEquals("1.0.42", release.versionName)
        assertEquals("Fix things", release.notes)
        assertEquals("https://example.com/ConnectIt.apk", release.apkUrl)
        assertEquals(1234L, release.apkSizeBytes)
        assertEquals("sha256:abcd", release.digest)
    }

    @Test
    fun parseRelease_withoutApkAsset_returnsNull() {
        assertNull(AppUpdater.parseRelease("""{"tag_name": "v1.0.3", "assets": []}"""))
        assertNull(AppUpdater.parseRelease("""{"tag_name": "nightly", "assets": [{"name": "ConnectIt.apk", "browser_download_url": "x"}]}"""))
    }

    @Test
    fun verifyDigest_rejectsMismatch() {
        val hash = MessageDigest.getInstance("SHA-256").digest("hello".toByteArray())
        AppUpdater.verifyDigest(hash, "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
        AppUpdater.verifyDigest(hash, null)
        assertThrows(IllegalStateException::class.java) { AppUpdater.verifyDigest(hash, "sha256:00") }
    }
}
