package com.connectit.android.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidationTest {

    @Test
    fun port_emptyMeansAutoAssign() {
        val result = SettingsValidation.validatePort("  ")
        assertTrue(result.isValid)
        assertEquals(0, result.value)
    }

    @Test
    fun port_requiresNonPrivilegedRange() {
        assertTrue(SettingsValidation.validatePort("1024").isValid)
        assertTrue(SettingsValidation.validatePort("65535").isValid)
        assertFalse(SettingsValidation.validatePort("80").isValid)
        assertFalse(SettingsValidation.validatePort("70000").isValid)
        assertNotNull(SettingsValidation.validatePort("80").error)
    }

    @Test
    fun connectTimeout_usesSettingsRange() {
        assertTrue(SettingsValidation.validateConnectTimeout("3").isValid)
        assertTrue(SettingsValidation.validateConnectTimeout("120").isValid)
        assertFalse(SettingsValidation.validateConnectTimeout("2").isValid)
        assertFalse(SettingsValidation.validateConnectTimeout("").isValid)
    }

    @Test
    fun autoplayCountdown_usesPlaybackOptionsRange() {
        assertEquals(5, SettingsValidation.validateAutoplayCountdown(" 5 ").value)
        assertFalse(SettingsValidation.validateAutoplayCountdown("0").isValid)
        assertFalse(SettingsValidation.validateAutoplayCountdown("31").isValid)
    }

    @Test
    fun deviceName_rejectsBlankAndTooLong() {
        assertNull(SettingsValidation.validateDeviceName("客廳電視"))
        assertNotNull(SettingsValidation.validateDeviceName("   "))
        assertNotNull(SettingsValidation.validateDeviceName("a".repeat(SettingsValidation.MAX_DEVICE_NAME_LENGTH + 1)))
    }
}
