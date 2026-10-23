package com.bridge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStateTest {

    @Test
    fun resolve_bluetoothOff_winsOverEverything() {
        assertEquals(
            LinkState.BLUETOOTH_OFF,
            LinkState.resolve(isBluetoothOn = false, hasTrustedMac = true, isSocketConnected = true, isVerified = true)
        )
    }

    @Test
    fun resolve_noTrustedMac_isUnpaired() {
        assertEquals(
            LinkState.UNPAIRED,
            LinkState.resolve(isBluetoothOn = true, hasTrustedMac = false, isSocketConnected = false, isVerified = false)
        )
    }

    @Test
    fun resolve_trustedButDown_isDisconnected() {
        assertEquals(
            LinkState.DISCONNECTED,
            LinkState.resolve(isBluetoothOn = true, hasTrustedMac = true, isSocketConnected = false, isVerified = false)
        )
    }

    @Test
    fun resolve_socketOpenButUnverified_isConnecting_notConnected() {
        // The bug this fixes: an open socket to an untrusted peer delivers nothing.
        assertEquals(
            LinkState.CONNECTING,
            LinkState.resolve(isBluetoothOn = true, hasTrustedMac = false, isSocketConnected = true, isVerified = false)
        )
    }

    @Test
    fun resolve_verifiedLink_isConnected() {
        assertEquals(
            LinkState.CONNECTED,
            LinkState.resolve(isBluetoothOn = true, hasTrustedMac = true, isSocketConnected = true, isVerified = true)
        )
    }

    @Test
    fun resolve_verifiedWinsEvenIfTrustStoreLooksEmpty() {
        assertEquals(
            LinkState.CONNECTED,
            LinkState.resolve(isBluetoothOn = true, hasTrustedMac = false, isSocketConnected = true, isVerified = true)
        )
    }

    @Test
    fun needsAttention_onlyFalseWhenConnected() {
        assertFalse(LinkState.CONNECTED.needsAttention)
        assertTrue(LinkState.CONNECTING.needsAttention)
        assertTrue(LinkState.DISCONNECTED.needsAttention)
        assertTrue(LinkState.UNPAIRED.needsAttention)
        assertTrue(LinkState.BLUETOOTH_OFF.needsAttention)
    }

    @Test
    fun actionLabel_mapsEachState() {
        assertEquals("Turn on Bluetooth", LinkState.actionLabel(LinkState.BLUETOOTH_OFF))
        assertEquals("Pair Mac", LinkState.actionLabel(LinkState.UNPAIRED))
        assertEquals("Reconnect", LinkState.actionLabel(LinkState.DISCONNECTED))
        assertEquals("Reconnect", LinkState.actionLabel(LinkState.CONNECTING))
        assertEquals("Connected", LinkState.actionLabel(LinkState.CONNECTED))
    }

    @Test
    fun pillText_isHonest_connectingNotConnected() {
        assertEquals("Connecting…", LinkState.pillText(LinkState.CONNECTING))
        assertEquals("Connected", LinkState.pillText(LinkState.CONNECTED))
    }

    @Test
    fun pillColor_greenOnlyWhenConnected() {
        assertEquals("#4CAF50", LinkState.pillColorHex(LinkState.CONNECTED))
        assertFalse("#4CAF50" == LinkState.pillColorHex(LinkState.CONNECTING))
        assertFalse("#4CAF50" == LinkState.pillColorHex(LinkState.BLUETOOTH_OFF))
    }
}
