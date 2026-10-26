package com.bridge.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cache used to consume on read (`replyCache.remove`), so the second reply to a
 * conversation was a silent cache miss and — with no ack packet — the Mac reported it
 * as sent. These lock that down.
 */
class ReplyActionCacheTest {

    private data class Entry(val notificationKey: String, val label: String = "")

    private fun cache(max: Int = ReplyActionCache.DEFAULT_MAX_ENTRIES) =
        ReplyActionCache<Entry>(max) { it.notificationKey }

    @Test
    fun `get does not consume the entry`() {
        val c = cache()
        c.put("reply-1", Entry("notif-1"))

        assertNotNull("first reply should find the action", c.get("reply-1"))
        assertNotNull("second reply to the same notification should too", c.get("reply-1"))
        assertNotNull("and a third", c.get("reply-1"))
        assertEquals(1, c.size)
    }

    @Test
    fun `unknown reply id returns null`() {
        assertNull(cache().get("never-seen"))
    }

    @Test
    fun `evicting a notification drops its entries`() {
        val c = cache()
        c.put("reply-1", Entry("notif-1"))
        c.put("reply-2", Entry("notif-1"))
        c.put("reply-3", Entry("notif-2"))

        c.evictNotification("notif-1")

        assertNull(c.get("reply-1"))
        assertNull(c.get("reply-2"))
        assertNotNull("an unrelated notification must survive", c.get("reply-3"))
        assertEquals(1, c.size)
    }

    @Test
    fun `evicting an unknown notification changes nothing`() {
        val c = cache()
        c.put("reply-1", Entry("notif-1"))

        c.evictNotification("notif-does-not-exist")

        assertNotNull(c.get("reply-1"))
        assertEquals(1, c.size)
    }

    @Test
    fun `cache is bounded`() {
        val c = cache(max = 3)
        repeat(5) { i -> c.put("reply-$i", Entry("notif-$i")) }

        assertEquals(3, c.size)
        assertNull("oldest should have been evicted", c.get("reply-0"))
        assertNotNull(c.get("reply-4"))
    }

    @Test
    fun `reading keeps an entry from being evicted as eldest`() {
        val c = cache(max = 3)
        c.put("reply-0", Entry("notif-0"))
        c.put("reply-1", Entry("notif-1"))
        c.put("reply-2", Entry("notif-2"))

        // Touch the oldest so access order makes reply-1 the eldest instead.
        assertNotNull(c.get("reply-0"))
        c.put("reply-3", Entry("notif-3"))

        assertNotNull("recently read entry should survive", c.get("reply-0"))
        assertNull("untouched eldest should be gone", c.get("reply-1"))
    }

    @Test
    fun `putting the same reply id twice replaces rather than duplicates`() {
        val c = cache()
        c.put("reply-1", Entry("notif-1", label = "first"))
        c.put("reply-1", Entry("notif-1", label = "second"))

        assertEquals(1, c.size)
        assertEquals("second", c.get("reply-1")?.label)
    }
}
