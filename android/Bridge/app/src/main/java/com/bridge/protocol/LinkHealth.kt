package com.bridge.protocol

/**
 * Richer link-health classification for the Android-side watchdog. Distinguishes
 * a clean disconnect from a silent death (BLE connected but no packets arriving).
 */
sealed class LinkHealth {
    object Connected : LinkHealth()
    data class Stale(val seconds: Int) : LinkHealth()
    object Dead : LinkHealth()
    object Disconnected : LinkHealth()
}

object LinkHealthEvaluator {
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val STALE_THRESHOLD_MS = 60_000L
    const val DEAD_THRESHOLD_MS = 90_000L

    /** Classify the link from the last packet time and transport state. */
    fun evaluate(
        lastSeenMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        connected: Boolean
    ): LinkHealth {
        if (!connected) return LinkHealth.Disconnected
        if (lastSeenMs == null) return LinkHealth.Connected
        val elapsed = nowMs - lastSeenMs
        return when {
            elapsed >= DEAD_THRESHOLD_MS -> LinkHealth.Dead
            elapsed >= STALE_THRESHOLD_MS -> LinkHealth.Stale((elapsed / 1000).toInt())
            else -> LinkHealth.Connected
        }
    }

    /** Foreground-service notification text for the current health. */
    fun notificationText(health: LinkHealth, macName: String): String = when (health) {
        is LinkHealth.Connected -> "Bridge active — connected to $macName"
        is LinkHealth.Stale -> "Bridge active — connected to $macName"
        is LinkHealth.Dead -> "Mac connection lost"
        is LinkHealth.Disconnected -> "Mac connection lost"
    }
}
