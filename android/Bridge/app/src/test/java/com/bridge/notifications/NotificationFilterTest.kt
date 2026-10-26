package com.bridge.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    // --- FilterState.shouldForward: the deny-by-default gate ---

    @Test
    fun `disallowed app is not forwarded`() {
        val state = FilterState(allowed = setOf("com.a"), masterMute = false)
        assertFalse(state.shouldForward("com.b"))
    }

    @Test
    fun `allowed app with mute off is forwarded`() {
        val state = FilterState(allowed = setOf("com.a"), masterMute = false)
        assertTrue(state.shouldForward("com.a"))
    }

    @Test
    fun `newly seen app defaults to silent`() {
        // seen but never allowed
        val state = FilterState().withSeen("com.new")
        assertFalse(state.shouldForward("com.new"))
        assertTrue(state.seen.contains("com.new"))
    }

    @Test
    fun `master mute suppresses an allowed app`() {
        val state = FilterState(allowed = setOf("com.a"), masterMute = true)
        assertFalse(state.shouldForward("com.a"))
    }

    @Test
    fun `turning master mute off restores per-app behavior`() {
        val muted = FilterState(allowed = setOf("com.a"), masterMute = true)
        val unmuted = muted.withMasterMute(false)
        assertTrue(unmuted.shouldForward("com.a"))
        assertFalse(unmuted.shouldForward("com.b"))
    }

    // --- transitions are immutable and correct ---

    @Test
    fun `withAllowed adds and removes`() {
        val added = FilterState().withAllowed("com.a", true)
        assertTrue(added.isAllowed("com.a"))
        val removed = added.withAllowed("com.a", false)
        assertFalse(removed.isAllowed("com.a"))
    }

    @Test
    fun `withSeen is idempotent`() {
        val once = FilterState().withSeen("com.a")
        val twice = once.withSeen("com.a")
        assertEquals(once, twice)
        assertEquals(setOf("com.a"), twice.seen)
    }

    @Test
    fun `transitions do not mutate the original`() {
        val original = FilterState()
        original.withAllowed("com.a", true)
        original.withSeen("com.a")
        original.withMasterMute(true)
        assertEquals(FilterState(), original)
    }

    // --- composition with iter-3 structural skips (task 2.3) ---

    @Test
    fun `ongoing notification is structurally skipped`() {
        assertTrue(NotificationGate.isStructuralSkip(isOngoing = true, isForegroundService = false, isGroupSummary = false))
    }

    @Test
    fun `foreground service and group summary are structurally skipped`() {
        assertTrue(NotificationGate.isStructuralSkip(isOngoing = false, isForegroundService = true, isGroupSummary = false))
        assertTrue(NotificationGate.isStructuralSkip(isOngoing = false, isForegroundService = false, isGroupSummary = true))
    }

    @Test
    fun `plain notification is not structurally skipped`() {
        assertFalse(NotificationGate.isStructuralSkip(isOngoing = false, isForegroundService = false, isGroupSummary = false))
    }

    // --- BR-6: one-tap escape from the all-denied state ---

    @Test
    fun `enabling all allows every seen app`() {
        val state = FilterState(seen = setOf("com.a", "com.b", "com.c"))
        val after = state.withAllSeenAllowed()

        assertEquals(setOf("com.a", "com.b", "com.c"), after.allowed)
        assertTrue(after.shouldForward("com.a"))
        assertTrue(after.shouldForward("com.c"))
    }

    @Test
    fun `enabling all keeps apps that were already allowed`() {
        val state = FilterState(allowed = setOf("com.a"), seen = setOf("com.a", "com.b"))
        assertEquals(setOf("com.a", "com.b"), state.withAllSeenAllowed().allowed)
    }

    @Test
    fun `enabling all with nothing seen changes nothing`() {
        val state = FilterState()
        assertEquals(state, state.withAllSeenAllowed())
    }

    @Test
    fun `enabling all does not touch master mute`() {
        val muted = FilterState(masterMute = true, seen = setOf("com.a"))
        val after = muted.withAllSeenAllowed()

        assertTrue("pausing and enabling are separate choices", after.masterMute)
        assertFalse("an allowed app stays paused while muted", after.shouldForward("com.a"))
    }

    @Test
    fun `enabling all does not allow an app that has never been seen`() {
        val state = FilterState(seen = setOf("com.a"))
        assertFalse(state.withAllSeenAllowed().isAllowed("com.never-notified"))
    }

    @Test
    fun `allowed app ongoing notification is still dropped by the structural skip`() {
        // Models the listener order: structural skip runs before the filter gate,
        // so an allowed app's ongoing notification never reaches shouldForward.
        val state = FilterState(allowed = setOf("com.a"))
        val structurallySkipped =
            NotificationGate.isStructuralSkip(isOngoing = true, isForegroundService = false, isGroupSummary = false)
        val forwarded = !structurallySkipped && state.shouldForward("com.a")
        assertFalse(forwarded)
    }
}
