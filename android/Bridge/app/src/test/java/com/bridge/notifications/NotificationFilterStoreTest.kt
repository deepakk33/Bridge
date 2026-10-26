package com.bridge.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [FilterPersistence] standing in for SharedPreferences (JVM-only tests). */
private class FakePersistence(
    var allowed: Set<String> = emptySet(),
    var muted: Boolean = false,
    var seen: Set<String> = emptySet()
) : FilterPersistence {
    override fun load() = FilterState(allowed, muted, seen)
    override fun saveAllowed(allowed: Set<String>) { this.allowed = allowed }
    override fun saveMasterMute(muted: Boolean) { this.muted = muted }
    override fun saveSeen(seen: Set<String>) { this.seen = seen }
}

class NotificationFilterStoreTest {

    @Test
    fun `loads persisted state at construction and reads synchronously`() {
        val persistence = FakePersistence(allowed = setOf("com.a"), muted = true, seen = setOf("com.a", "com.b"))
        val store = NotificationFilterStore(persistence)

        assertTrue(store.isAllowed("com.a"))
        assertFalse(store.isAllowed("com.b"))
        assertTrue(store.isMuted())
        assertEquals(setOf("com.a", "com.b"), store.state.value.seen)
    }

    @Test
    fun `setAllowed updates state and persists`() {
        val persistence = FakePersistence()
        val store = NotificationFilterStore(persistence)

        store.setAllowed("com.a", true)
        assertTrue(store.isAllowed("com.a"))
        assertTrue(persistence.allowed.contains("com.a"))

        store.setAllowed("com.a", false)
        assertFalse(store.isAllowed("com.a"))
        assertFalse(persistence.allowed.contains("com.a"))
    }

    @Test
    fun `setMasterMute updates state and persists`() {
        val persistence = FakePersistence()
        val store = NotificationFilterStore(persistence)

        store.setMasterMute(true)
        assertTrue(store.isMuted())
        assertTrue(persistence.muted)
    }

    @Test
    fun `recordSeen persists and is idempotent`() {
        val persistence = FakePersistence()
        val store = NotificationFilterStore(persistence)

        store.recordSeen("com.a")
        store.recordSeen("com.a")
        assertEquals(setOf("com.a"), persistence.seen)
        assertEquals(setOf("com.a"), store.state.value.seen)
    }

    @Test
    fun `state round-trips through a fresh store (survives restart)`() {
        val persistence = FakePersistence()
        NotificationFilterStore(persistence).apply {
            setAllowed("com.msg", true)
            setMasterMute(true)
            recordSeen("com.msg")
        }

        // Simulate app restart: new store loads from the same persistence.
        val reloaded = NotificationFilterStore(persistence)
        assertTrue(reloaded.isAllowed("com.msg"))
        assertTrue(reloaded.isMuted())
        assertTrue(reloaded.state.value.seen.contains("com.msg"))
    }

    @Test
    fun `default state is deny-by-default and unmuted`() {
        val store = NotificationFilterStore(FakePersistence())
        assertFalse(store.isMuted())
        assertFalse(store.shouldForward("com.anything"))
    }

    // --- BR-6: one-tap escape from the all-denied state ---

    @Test
    fun `allowAllSeen allows every seen app and persists once`() {
        val persistence = FakePersistence(seen = setOf("com.a", "com.b", "com.c"))
        val store = NotificationFilterStore(persistence)

        store.allowAllSeen()

        assertEquals(setOf("com.a", "com.b", "com.c"), store.state.value.allowed)
        assertEquals("the write must reach persistence", setOf("com.a", "com.b", "com.c"), persistence.allowed)
        assertTrue(store.shouldForward("com.b"))
    }

    @Test
    fun `allowAllSeen with nothing seen does not write`() {
        val persistence = FakePersistence()
        val store = NotificationFilterStore(persistence)

        store.allowAllSeen()

        assertTrue(store.state.value.allowed.isEmpty())
        assertTrue(persistence.allowed.isEmpty())
    }

    @Test
    fun `allowAllSeen leaves master mute alone`() {
        val persistence = FakePersistence(muted = true, seen = setOf("com.a"))
        val store = NotificationFilterStore(persistence)

        store.allowAllSeen()

        assertTrue(store.isMuted())
        assertFalse("allowed but still paused", store.shouldForward("com.a"))
    }

    @Test
    fun `allowAllSeen survives a restart`() {
        val persistence = FakePersistence(seen = setOf("com.a", "com.b"))
        NotificationFilterStore(persistence).allowAllSeen()

        val reopened = NotificationFilterStore(persistence)
        assertTrue(reopened.isAllowed("com.a"))
        assertTrue(reopened.isAllowed("com.b"))
    }
}
