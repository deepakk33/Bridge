package com.bridge.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCardStateTest {

    private val truncated = SendOutcome(originalBytes = 43_008, truncated = true)

    @Test
    fun `all well when granted, linked and nothing was cut`() {
        val state = ClipboardCardState.of(isWatching = true, isLinkUp = true, lastSend = null)
        assertEquals(ClipboardCardState.Kind.SYNCING, state.kind)
        assertFalse(state.isWarning)
        assertNull(state.formatArg)
    }

    @Test
    fun `manual sync when no watcher is running`() {
        // Not a warning: on Android 16 background clipboard access is role-managed, so manual
        // is the permanent correct state and a permanent amber would train the user to ignore it.
        val state = ClipboardCardState.of(isWatching = false, isLinkUp = true, lastSend = null)
        assertEquals(ClipboardCardState.Kind.MANUAL_NO_GRANT, state.kind)
        assertFalse(state.isWarning)
    }

    @Test
    fun `truncation is reported when granted and linked`() {
        val state = ClipboardCardState.of(isWatching = true, isLinkUp = true, lastSend = truncated)
        assertEquals(ClipboardCardState.Kind.TRUNCATED, state.kind)
        assertTrue(state.isWarning)
        assertEquals("42 KB", state.formatArg)
    }

    @Test
    fun `link down beats a missing grant`() {
        val state = ClipboardCardState.of(isWatching = false, isLinkUp = false, lastSend = null)
        assertEquals(ClipboardCardState.Kind.LINK_DOWN, state.kind)
        assertTrue(state.isWarning)
    }

    @Test
    fun `link down beats a truncated send`() {
        val state = ClipboardCardState.of(isWatching = true, isLinkUp = false, lastSend = truncated)
        assertEquals(ClipboardCardState.Kind.LINK_DOWN, state.kind)
    }

    @Test
    fun `a truncated send is reported even with no watcher running`() {
        // The manual send button is the main path when there is no grant, so this is the
        // case most likely to truncate; the manual-sync notice must not hide it.
        val state = ClipboardCardState.of(isWatching = false, isLinkUp = true, lastSend = truncated)
        assertEquals(ClipboardCardState.Kind.TRUNCATED, state.kind)
        assertTrue(state.isWarning)
        assertEquals("42 KB", state.formatArg)
    }

    @Test
    fun `a granted-but-not-yet-watching bridge still reads as manual`() {
        // ADB grant applied after the service started: the card must not promise automatic
        // sync that no running watcher provides.
        val state = ClipboardCardState.of(isWatching = false, isLinkUp = true, lastSend = null)
        assertEquals(ClipboardCardState.Kind.MANUAL_NO_GRANT, state.kind)
    }

    @Test
    fun `an untruncated last send is not a warning`() {
        val whole = SendOutcome(originalBytes = 12, truncated = false)
        val state = ClipboardCardState.of(isWatching = true, isLinkUp = true, lastSend = whole)
        assertEquals(ClipboardCardState.Kind.SYNCING, state.kind)
        assertFalse(state.isWarning)
    }

    @Test
    fun `sizes are formatted for a human`() {
        assertEquals("999 B", ClipboardCardState.formatBytes(999))
        assertEquals("10 KB", ClipboardCardState.formatBytes(10_240))
        assertEquals("42 KB", ClipboardCardState.formatBytes(43_008))
        assertEquals("1.5 MB", ClipboardCardState.formatBytes(1_572_864))
    }
}
