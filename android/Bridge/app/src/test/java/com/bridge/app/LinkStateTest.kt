package com.bridge.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkStateTest {

    @Test
    fun resolve_noTrustedMac_isUnpaired() {
        assertEquals(LinkState.UNPAIRED, LinkState.resolve(hasTrustedMac = false, isConnected = false))
    }

    @Test
    fun resolve_trustedButDown_isDisconnected() {
        assertEquals(LinkState.DISCONNECTED, LinkState.resolve(hasTrustedMac = true, isConnected = false))
    }

    @Test
    fun resolve_connected_isConnected() {
        assertEquals(LinkState.CONNECTED, LinkState.resolve(hasTrustedMac = true, isConnected = true))
    }

    @Test
    fun resolve_connectedEvenWithoutTrust_isConnected() {
        // A live link wins: a peer may connect then re-pair after losing its trust store.
        assertEquals(LinkState.CONNECTED, LinkState.resolve(hasTrustedMac = false, isConnected = true))
    }

    @Test
    fun actionLabel_mapsEachState() {
        assertEquals("Pair Mac", LinkState.actionLabel(LinkState.UNPAIRED))
        assertEquals("Reconnect", LinkState.actionLabel(LinkState.DISCONNECTED))
        assertEquals("Connected", LinkState.actionLabel(LinkState.CONNECTED))
    }
}
