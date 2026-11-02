package com.bridge.filetransfer

import com.bridge.security.TrustStoreAccess
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BR-19. `pairedFingerprint()` took the first entry of an unordered map, so with two
 * devices pinned the payload connection could be pinned to the wrong peer. The pin is
 * now looked up by the deviceId on the control connection.
 */
class PayloadTlsTest {

    private class FakeTrustStore(private val pins: Map<String, String>) : TrustStoreAccess {
        override fun isTrusted(deviceId: String, fingerprint: String) = pins[deviceId] == fingerprint
        override fun pinnedFingerprint(deviceId: String): String? = pins[deviceId]
        override fun pin(deviceId: String, fingerprint: String) = Unit
        override fun unpin(deviceId: String) = Unit
        override val isEmpty: Boolean get() = pins.isEmpty()
    }

    private val fingerprintA = "aa".repeat(32)
    private val fingerprintB = "bb".repeat(32)
    private val twoPinned = FakeTrustStore(mapOf("mac-a" to fingerprintA, "mac-b" to fingerprintB))

    @Test
    fun `with two devices pinned the named peer's fingerprint is used`() {
        // Repeated, because the old code failed by picking an arbitrary key — a single
        // pass could pick the right one by luck.
        repeat(20) {
            assertEquals(fingerprintA, PayloadTls.pinFor("mac-a", twoPinned))
            assertEquals(fingerprintB, PayloadTls.pinFor("mac-b", twoPinned))
        }
    }

    @Test(expected = PayloadTls.NotPairedException::class)
    fun `an unpinned peer is refused while another device is pinned`() {
        PayloadTls.pinFor("mac-stranger", twoPinned)
    }

    @Test(expected = PayloadTls.NotPairedException::class)
    fun `an empty trust store refuses`() {
        PayloadTls.pinFor("mac-a", FakeTrustStore(emptyMap()))
    }
}
