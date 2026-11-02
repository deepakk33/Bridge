package com.bridge.security

import com.bridge.protocol.BridgePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BR-5: nothing is pinned until both this device's user and the peer have accepted.
 * The old code called acceptPair() straight from the onPairRequest callback, so these
 * tests are mostly about what must *not* happen.
 */
class PairingManagerTest {

    private class FakeTrustStore : TrustStoreAccess {
        val pins = mutableMapOf<String, String>()
        override fun isTrusted(deviceId: String, fingerprint: String) = pins[deviceId] == fingerprint
        override fun pinnedFingerprint(deviceId: String): String? = pins[deviceId]
        override fun pin(deviceId: String, fingerprint: String) { pins[deviceId] = fingerprint }
        override fun unpin(deviceId: String) { pins.remove(deviceId) }
        override val isEmpty: Boolean get() = pins.isEmpty()
    }

    private val macFingerprint = "aa".repeat(32)
    private val phoneFingerprint = "bb".repeat(32)
    private val otherFingerprint = "cc".repeat(32)

    private lateinit var store: FakeTrustStore
    private lateinit var sent: MutableList<BridgePacket>
    private lateinit var manager: PairingManager
    private var confirmations = mutableListOf<Pair<String, String>>()
    private var mismatches = mutableListOf<String>()

    @Before
    fun setUp() {
        store = FakeTrustStore()
        sent = mutableListOf()
        confirmations = mutableListOf()
        mismatches = mutableListOf()
        manager = PairingManager(
            localDeviceId = "phone-1",
            localFingerprint = { phoneFingerprint },
            trustStore = { store },
            scope = CoroutineScope(Dispatchers.Unconfined),
            sendPacket = { sent.add(it) }
        )
        manager.onPairRequest = { name, _, code -> confirmations.add(name to code) }
        manager.onFingerprintMismatch = { mismatches.add(it) }
    }

    private fun identifyUnknownMac() {
        manager.setPeerIdentity("mac-1", "MacBook Air", macFingerprint)
    }

    private fun sentPairValues() = sent.mapNotNull { it.body["pair"]?.jsonPrimitive?.booleanOrNull }

    // --- Both decisions required, either order ---

    @Test
    fun `local then remote accept pins`() {
        identifyUnknownMac()
        manager.acceptPair()
        assertFalse("one acceptance is not two", manager.state == PairingManager.State.PAIRED)

        manager.handlePairPacket(BridgePacket.pairAccept())
        assertEquals(PairingManager.State.PAIRED, manager.state)
        assertEquals(macFingerprint, store.pinnedFingerprint("mac-1"))
    }

    @Test
    fun `remote then local accept pins`() {
        identifyUnknownMac()
        manager.handlePairPacket(BridgePacket.pairRequest())
        assertTrue("the peer's acceptance alone is not consent", store.isEmpty)

        manager.acceptPair()
        assertEquals(PairingManager.State.PAIRED, manager.state)
        assertEquals(macFingerprint, store.pinnedFingerprint("mac-1"))
    }

    /** The exact shape of the shipped bug: the callback called acceptPair() itself. */
    @Test
    fun `remote accept alone never pins`() {
        identifyUnknownMac()
        manager.handlePairPacket(BridgePacket.pairRequest())
        manager.handlePairPacket(BridgePacket.pairAccept())

        assertTrue(store.isEmpty)
        assertFalse(manager.state == PairingManager.State.PAIRED)
        assertTrue("the user should still be being asked", confirmations.isNotEmpty())
    }

    // --- Declining ---

    @Test
    fun `local decline leaves both stores unchanged`() {
        identifyUnknownMac()
        manager.handlePairPacket(BridgePacket.pairRequest())
        manager.rejectPair()

        assertTrue(store.isEmpty)
        assertTrue("peer must be told", sentPairValues().contains(false))

        // A later accept from the peer must not resurrect the declined pairing.
        manager.handlePairPacket(BridgePacket.pairAccept())
        assertTrue(store.isEmpty)
    }

    @Test
    fun `remote decline leaves both stores unchanged`() {
        identifyUnknownMac()
        manager.acceptPair()
        manager.handlePairPacket(BridgePacket.pairReject())

        assertTrue(store.isEmpty)
        assertFalse(manager.state == PairingManager.State.PAIRED)
    }

    // --- An empty trust store is not consent ---

    @Test
    fun `empty trust store does not auto pair`() {
        assertTrue(store.isEmpty)
        identifyUnknownMac()

        assertTrue("first run is still a human decision", store.isEmpty)
        assertFalse(manager.state == PairingManager.State.PAIRED)
        assertEquals(1, confirmations.size)
        assertFalse("nothing affirmed yet", sentPairValues().contains(true))
    }

    @Test
    fun `incoming request on empty store waits for explicit accept`() {
        identifyUnknownMac()
        manager.handlePairPacket(BridgePacket.pairRequest())
        assertTrue(store.isEmpty)

        manager.acceptPair()
        assertEquals(macFingerprint, store.pinnedFingerprint("mac-1"))
    }

    // --- Fingerprint mismatch ---

    @Test
    fun `pinned device with changed fingerprint is refused`() {
        store.pin("mac-1", macFingerprint)
        manager.setPeerIdentity("mac-1", "MacBook Air", otherFingerprint)

        assertEquals(listOf("MacBook Air"), mismatches)
        assertEquals(
            "the pin must survive, so re-pairing stays deliberate",
            macFingerprint, store.pinnedFingerprint("mac-1")
        )
        assertTrue("a mismatch is not a pairing offer", confirmations.isEmpty())
        assertFalse(manager.state == PairingManager.State.PAIRED)
    }

    @Test
    fun `already paired device reconnects silently`() {
        store.pin("mac-1", macFingerprint)
        identifyUnknownMac()

        assertEquals(PairingManager.State.PAIRED, manager.state)
        assertTrue(confirmations.isEmpty())
    }

    // --- Crossed initiation ---

    @Test
    fun `crossed request still converges when both users accept`() {
        identifyUnknownMac()
        manager.acceptPair()                                   // we send a request
        manager.handlePairPacket(BridgePacket.pairRequest())   // theirs crosses ours

        assertEquals(PairingManager.State.PAIRED, manager.state)
        assertEquals(macFingerprint, store.pinnedFingerprint("mac-1"))
    }

    // --- Timeout scope ---

    /**
     * The 30-second timer only runs in AWAITING_RESPONSE, which is only entered after
     * the local user accepts. A prompt nobody has answered must not expire.
     */
    @Test
    fun `pending local prompt does not enter the timed state`() {
        identifyUnknownMac()
        manager.handlePairPacket(BridgePacket.pairRequest())

        assertFalse(
            "a prompt awaiting this device's user must not be on the peer timeout",
            manager.state == PairingManager.State.AWAITING_RESPONSE
        )
        assertTrue(confirmations.isNotEmpty())
    }

    @Test
    fun `accepting enters the timed state`() {
        identifyUnknownMac()
        manager.acceptPair()
        assertEquals(PairingManager.State.AWAITING_RESPONSE, manager.state)
    }

    // --- The code reaches the UI ---

    @Test
    fun `confirmation carries the shared code`() {
        identifyUnknownMac()
        assertEquals(PairingCode.derive(phoneFingerprint, macFingerprint), confirmations.single().second)
    }

    @Test
    fun `no confirmation without a local fingerprint`() {
        val bare = PairingManager(
            localDeviceId = "phone-1",
            localFingerprint = { null },
            trustStore = { store },
            scope = CoroutineScope(Dispatchers.Unconfined),
            sendPacket = { sent.add(it) }
        )
        var asked = false
        bare.onPairRequest = { _, _, _ -> asked = true }
        bare.setPeerIdentity("mac-1", "MacBook Air", macFingerprint)

        assertFalse("no code means no confirmation, so no pairing", asked)
        assertTrue(store.isEmpty)
        assertNull(bare.verificationCode)
    }

    /**
     * BR-19. The keystore read can fail while the service is still starting, and `by
     * lazy` used to memoise that null for the life of the process — pairing was dead
     * until a restart. The read is retried, and the held peer presented when it lands.
     */
    @Test
    fun `a transient fingerprint failure is retried on the next pair request`() {
        var reads = 0
        val flaky = PairingManager(
            localDeviceId = "phone-1",
            localFingerprint = { if (reads++ == 0) null else phoneFingerprint },
            trustStore = { store },
            scope = CoroutineScope(Dispatchers.Unconfined),
            sendPacket = { sent.add(it) }
        )
        val asked = mutableListOf<Pair<String, String>>()
        flaky.onPairRequest = { name, _, code -> asked.add(name to code) }

        flaky.setPeerIdentity("mac-1", "MacBook Air", macFingerprint)
        assertTrue("the first read failed, so nothing can be shown yet", asked.isEmpty())

        flaky.handlePairPacket(BridgePacket.pairRequest())

        assertEquals(1, asked.size)
        assertEquals("MacBook Air", asked.single().first)
        assertEquals(
            PairingCode.derive(phoneFingerprint, macFingerprint),
            asked.single().second
        )
        assertTrue("nothing is pinned without a human", store.isEmpty)
    }

    /** A peer that leaves while its request is held must raise no prompt afterwards. */
    @Test
    fun `a held confirmation is dropped on reset`() {
        var reads = 0
        val flaky = PairingManager(
            localDeviceId = "phone-1",
            localFingerprint = { if (reads++ == 0) null else phoneFingerprint },
            trustStore = { store },
            scope = CoroutineScope(Dispatchers.Unconfined),
            sendPacket = { sent.add(it) }
        )
        var asked = false
        flaky.onPairRequest = { _, _, _ -> asked = true }

        flaky.setPeerIdentity("mac-1", "MacBook Air", macFingerprint)
        flaky.reset()
        flaky.handlePairPacket(BridgePacket.pairRequest())

        assertFalse("a held request must not survive a disconnect", asked)
    }

    @Test
    fun `verification code is available once a peer is known`() {
        assertNull(manager.verificationCode)
        identifyUnknownMac()
        assertNotNull(manager.verificationCode)
    }
}
