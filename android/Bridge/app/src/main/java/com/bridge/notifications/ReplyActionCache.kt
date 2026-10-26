package com.bridge.notifications

/**
 * Pure, Android-free policy for the reply-action cache.
 *
 * Holds one entry per reply-capable notification, keyed by the `requestReplyId`
 * sent to the Mac. Two rules matter and both are the kind that break silently:
 *
 * - [get] does not consume. A RemoteInput stays usable for as long as its
 *   notification is live, so a conversation can be replied to more than once.
 * - Eviction is by *notification*, not by reply id: [evictNotification] drops
 *   every entry belonging to a notification once it is gone from the phone.
 *
 * Bounded at [maxEntries] in access order, so a long-running listener cannot
 * grow without limit. Kept free of Android types — the value is opaque and its
 * notification key is read through [notificationKeyOf] — so the policy is
 * unit-testable on the plain JVM, matching [FilterState].
 */
class ReplyActionCache<T>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val notificationKeyOf: (T) -> String
) {

    private val entries = object : LinkedHashMap<String, T>(maxEntries + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean =
            size > maxEntries
    }

    fun put(requestReplyId: String, value: T) {
        synchronized(entries) { entries[requestReplyId] = value }
    }

    /** Reads without removing, so a second reply to the same notification still works. */
    fun get(requestReplyId: String): T? = synchronized(entries) { entries[requestReplyId] }

    /** Drops every entry belonging to [notificationKey]. */
    fun evictNotification(notificationKey: String) {
        synchronized(entries) {
            entries.entries
                .filter { notificationKeyOf(it.value) == notificationKey }
                .map { it.key }
                .forEach { entries.remove(it) }
        }
    }

    val size: Int get() = synchronized(entries) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
