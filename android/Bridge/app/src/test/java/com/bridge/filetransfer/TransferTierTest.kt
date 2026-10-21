package com.bridge.filetransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTierTest {

    @Test
    fun `small file uses bluetooth`() {
        val d = TierRouter.decide(300_000, sharedNetworkAvailable = true)
        assertEquals(TransferTier.BLUETOOTH, d.tier)
        assertFalse(d.isSlowFallback)
    }

    @Test
    fun `boundary below threshold uses bluetooth`() {
        assertEquals(TransferTier.BLUETOOTH, TierRouter.decide(511_999, true).tier)
    }

    @Test
    fun `threshold exact uses wifi when available`() {
        val d = TierRouter.decide(512_000, sharedNetworkAvailable = true)
        assertEquals(TransferTier.WIFI_LAN, d.tier)
        assertFalse(d.isSlowFallback)
    }

    @Test
    fun `large file uses wifi`() {
        assertEquals(TransferTier.WIFI_LAN, TierRouter.decide(5_000_000, true).tier)
    }

    @Test
    fun `large file falls back to bluetooth with warning`() {
        val d = TierRouter.decide(2_000_000, sharedNetworkAvailable = false)
        assertEquals(TransferTier.BLUETOOTH, d.tier)
        assertTrue(d.isSlowFallback)
    }

    @Test
    fun `small file no network no fallback flag`() {
        val d = TierRouter.decide(100_000, sharedNetworkAvailable = false)
        assertEquals(TransferTier.BLUETOOTH, d.tier)
        assertFalse(d.isSlowFallback)
    }
}
