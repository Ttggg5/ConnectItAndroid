package com.connectit.android.repo

import org.junit.Assert.assertEquals
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
    fun parse_handlesMissingOrBrokenValues() {
        assertTrue(SettingsRepository.parseTrustedDevices(null).isEmpty())
        assertTrue(SettingsRepository.parseTrustedDevices("not json").isEmpty())
    }
}
