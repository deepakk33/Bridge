package com.bridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHealthTest {

    private val now = 1_000_000_000L

    @Test
    fun `connected when recent`() {
        val h = LinkHealthEvaluator.evaluate(now - 10_000, now, connected = true)
        assertEquals(LinkHealth.Connected, h)
    }

    @Test
    fun `stale after 60s`() {
        val h = LinkHealthEvaluator.evaluate(now - 65_000, now, connected = true)
        assertEquals(LinkHealth.Stale(65), h)
    }

    @Test
    fun `dead after 90s while connected`() {
        val h = LinkHealthEvaluator.evaluate(now - 95_000, now, connected = true)
        assertEquals(LinkHealth.Dead, h)
    }

    @Test
    fun `disconnected when not connected`() {
        val h = LinkHealthEvaluator.evaluate(now - 200_000, now, connected = false)
        assertEquals(LinkHealth.Disconnected, h)
    }

    @Test
    fun `dead notification says mac connection lost`() {
        assertEquals("Mac connection lost", LinkHealthEvaluator.notificationText(LinkHealth.Dead, "MacBook"))
    }

    @Test
    fun `connected notification names mac`() {
        val text = LinkHealthEvaluator.notificationText(LinkHealth.Connected, "MacBook")
        assertTrue(text.contains("MacBook"))
    }
}
