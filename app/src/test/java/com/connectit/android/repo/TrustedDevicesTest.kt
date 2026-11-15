package com.connectit.android.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDevicesTest {

    @Test
    fun encodeThenParse_roundTripsEntries() {
        val devices = listOf(
            TrustedDevice("abc", "我的電腦", "device-1"),
            TrustedDevice("def", "Pixel", null),
        )

        assertEquals(devices, SettingsRepository.parseTrustedDevices(SettingsRepository.encodeTrustedDevices(devices)))
    }

    @Test
    fun parse_usesWindowsFieldNames_andSkipsEntriesWithoutFingerprint() {
        val json = """[{"Fingerprint":"abc","Name":"PC","DeviceId":"x"},{"Name":"no fingerprint"}]"""

        assertEquals(listOf(TrustedDevice("abc", "PC", "x")), SettingsRepository.parseTrustedDevices(json))
    }

    @Test
    fun allowUnattended_roundTripsAndDefaultsToFalse() {
        val devices = listOf(TrustedDevice("abc", "PC", "x", allowUnattended = true))
        assertEquals(devices, SettingsRepository.parseTrustedDevices(SettingsRepository.encodeTrustedDevices(devices)))

        // Windows 端寫的 JSON(欄位名稱一致)
        val json = """[{"Fingerprint":"abc","Name":"PC","DeviceId":"x","AllowUnattended":true},{"Fingerprint":"def","Name":"Old"}]"""
        val parsed = SettingsRepository.parseTrustedDevices(json)
        assertTrue(parsed[0].allowUnattended)
        assertFalse(parsed[1].allowUnattended)
    }

    @Test
    fun isUnattendedAllowed_requiresMasterSwitchAndPerDeviceFlag() {
        val devices = listOf(TrustedDevice("abc", "PC", "x", allowUnattended = true), TrustedDevice("def", "Phone", "y"))
        val off = AppSettings(deviceName = "", themeMode = AppThemeMode.AUTO, trustedDevices = devices)
        val on = off.copy(unattendedAccessEnabled = true)

        assertFalse(off.isUnattendedAllowed("abc"))
        assertTrue(on.isUnattendedAllowed("ABC"))
        assertFalse(on.isUnattendedAllowed("def"))
        assertFalse(on.isUnattendedAllowed("zzz"))
        assertFalse(on.isUnattendedAllowed(null))
    }

    @Test
    fun withTrusted_keepsUnattendedOnRename_butNotWhenKeyChanges() {
        val start = listOf(TrustedDevice("abc", "舊名字", "device-1", allowUnattended = true))

        val renamed = SettingsRepository.withTrusted(start, "abc", "新名字", "device-1")
        assertEquals(listOf(TrustedDevice("abc", "新名字", "device-1", allowUnattended = true)), renamed)

        val rekeyed = SettingsRepository.withTrusted(renamed, "def", "新名字", "device-1")
        assertEquals(listOf(TrustedDevice("def", "新名字", "device-1", allowUnattended = false)), rekeyed)
    }

    @Test
    fun parse_handlesMissingOrBrokenValues() {
        assertTrue(SettingsRepository.parseTrustedDevices(null).isEmpty())
        assertTrue(SettingsRepository.parseTrustedDevices("not json").isEmpty())
    }
}
