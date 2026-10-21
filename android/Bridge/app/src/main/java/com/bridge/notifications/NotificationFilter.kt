package com.bridge.notifications

/**
 * Pure, Android-free model of the notification forwarding filter.
 *
 * Deny-by-default: a notification is forwarded only if its source app is in
 * [allowed] AND [masterMute] is off. [seen] records every app that has posted a
 * notification so it can be offered in the toggle UI, independent of allow state.
 *
 * All transitions return a new immutable [FilterState]; this type holds no
 * Android dependencies so the gate decision is unit-testable on the plain JVM.
 */
data class FilterState(
    val allowed: Set<String> = emptySet(),
    val masterMute: Boolean = false,
    val seen: Set<String> = emptySet()
) {
    fun isAllowed(pkg: String): Boolean = pkg in allowed

    /** Deny-by-default gate: master mute off AND the app is explicitly allowed. */
    fun shouldForward(pkg: String): Boolean = !masterMute && pkg in allowed

    fun withAllowed(pkg: String, allow: Boolean): FilterState =
        copy(allowed = if (allow) allowed + pkg else allowed - pkg)

    fun withMasterMute(muted: Boolean): FilterState =
        copy(masterMute = muted)

    fun withSeen(pkg: String): FilterState =
        if (pkg in seen) this else copy(seen = seen + pkg)
}

/**
 * Pure combinator for the iter-3 structural skips, kept Android-free so the
 * composition rule (an allowed app's ongoing notification is still dropped) is
 * unit-testable. The caller passes the resolved notification flags.
 */
object NotificationGate {
    /**
     * True when the notification must be dropped regardless of filter state:
     * iter-3 drops ongoing, foreground-service, and group-summary notifications.
     * Checked BEFORE recording the app as seen, so structural noise never
     * pollutes the toggle list.
     */
    fun isStructuralSkip(
        isOngoing: Boolean,
        isForegroundService: Boolean,
        isGroupSummary: Boolean
    ): Boolean = isOngoing || isForegroundService || isGroupSummary
}

/** Persistence boundary so the store logic can be exercised without Android. */
interface FilterPersistence {
    fun load(): FilterState
    fun saveAllowed(allowed: Set<String>)
    fun saveMasterMute(muted: Boolean)
    fun saveSeen(seen: Set<String>)
}
