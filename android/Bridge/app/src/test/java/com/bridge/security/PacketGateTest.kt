package com.bridge.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BR-4: default-deny. The shipped bug was structural — the trust check sat inside
 * `if (peerDeviceId != null && ...)`, so a peer that had said nothing about itself was
 * the one peer whose packets were never checked.
 */
class PacketGateTest {

    private class FakeTrustStore(private val pins: Map<String, String> = emptyMap()) : TrustStoreAccess {
        override fun isTrusted(deviceId: String, fingerprint: String) = pins[deviceId] == fingerprint
        override fun pinnedFingerprint(deviceId: String): String? = pins[deviceId]
        override fun pin(deviceId: String, fingerprint: String) = Unit
        override fun unpin(deviceId: String) = Unit
        override val isEmpty: Boolean get() = pins.isEmpty()
    }

    private val fp = "aa".repeat(32)
    private val trusted = { FakeTrustStore(mapOf("mac-1" to fp)) as TrustStoreAccess }
    private val empty = { FakeTrustStore() as TrustStoreAccess }
    private val throwing = {
        throw IllegalStateException("TrustStore not initialized")
        @Suppress("UNREACHABLE_CODE") FakeTrustStore() as TrustStoreAccess
    }

    private fun allows(type: String, id: String?, fingerprint: String?, store: () -> TrustStoreAccess) =
        PacketGate.allows(type, id, fingerprint, store)

    @Test
    fun `identity and pair pass from a stranger`() {
        assertTrue(allows("bridge.identity", null, null, empty))
        assertTrue(allows("bridge.pair", null, null, empty))
    }

    @Test
    fun `every other type is dropped before identity`() {
        for (type in listOf(
            "bridge.clipboard", "bridge.clipboard.connect", "bridge.notification.reply",
            "bridge.notification.request", "bridge.share.request", "bridge.battery"
        )) {
            assertFalse(type, allows(type, null, null, trusted))
        }
    }

    /**
     * Inverted by BR-19. The phone's 3-second probe needs an answer while a pair
     * confirmation sits open, and both sides already send pings to an unverified
     * peer, so refusing to receive one stranded the link without hiding anything.
     */
    @Test
    fun `ping passes from a stranger`() {
        assertTrue(allows("bridge.ping", null, null, trusted))
        assertTrue(allows("bridge.ping", null, null, empty))
    }

    /** Exempting ping must not exempt anything else. */
    @Test
    fun `an unverified peer gets ping and nothing else`() {
        assertFalse(allows("bridge.clipboard", null, null, trusted))
        assertFalse(allows("bridge.share.request", null, null, trusted))
    }

    @Test
    fun `identified but unpinned peer is dropped`() {
        assertFalse(allows("bridge.clipboard", "mac-1", fp, empty))
    }

    @Test
    fun `mismatched fingerprint is dropped`() {
        assertFalse(allows("bridge.clipboard", "mac-1", "bb".repeat(32), trusted))
    }

    @Test
    fun `trusted peer passes`() {
        assertTrue(allows("bridge.clipboard", "mac-1", fp, trusted))
        assertTrue(allows("bridge.ping", "mac-1", fp, trusted))
    }

    /** The swallowed IllegalStateException used to mean "skip the check". */
    @Test
    fun `unreadable trust store denies`() {
        assertFalse(allows("bridge.clipboard", "mac-1", fp, throwing))
    }

    /** Identity and pair must stay reachable, or a re-pair could never happen. */
    @Test
    fun `identity and pair pass even when the trust store is broken`() {
        assertTrue(allows("bridge.identity", "mac-1", fp, throwing))
        assertTrue(allows("bridge.pair", "mac-1", fp, throwing))
    }

    @Test
    fun `half an identity is no identity`() {
        assertFalse(allows("bridge.clipboard", "mac-1", null, trusted))
        assertFalse(allows("bridge.clipboard", null, fp, trusted))
    }
}
