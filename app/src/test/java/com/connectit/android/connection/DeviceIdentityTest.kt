package com.connectit.android.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdentityTest {

    // 跟 Windows 端 SecureConnectionTests 用同一組測試向量,確保兩邊算出一樣的配對碼。
    @Test
    fun pairingCode_matchesSharedTestVectors_regardlessOfOrder() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        assertEquals("979741", DeviceIdentity.pairingCode(a, b))
        assertEquals("979741", DeviceIdentity.pairingCode(b, a))

        val c = "0f".repeat(32)
        val d = "e1".repeat(32)
        assertEquals("328450", DeviceIdentity.pairingCode(c, d))
        assertEquals("328450", DeviceIdentity.pairingCode(d, c))
    }

    @Test
    fun formatPairingCode_splitsIntoTwoGroups() {
        assertEquals("123 456", DeviceIdentity.formatPairingCode("123456"))
        assertEquals("12", DeviceIdentity.formatPairingCode("12"))
    }
}
