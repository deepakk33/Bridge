package com.bridge.filetransfer

/** Transfer tier for a file payload. */
enum class TransferTier {
    /** Tier 2 — Bluetooth Classic RFCOMM. No network required. */
    BLUETOOTH,

    /** Tier 3 — Wi-Fi LAN over mDNS + TCP. Full LAN throughput. */
    WIFI_LAN
}

/**
 * Result of tier selection.
 * [isSlowFallback] is true when a large file fell back to Tier 2 because no
 * shared network was available (user gets a slow-transfer warning).
 */
data class TierDecision(
    val tier: TransferTier,
    val isSlowFallback: Boolean
)

object TierRouter {
    /** Files below this size route to Tier 2; at or above, to Tier 3 when possible. */
    const val THRESHOLD_BYTES = 512_000L

    /**
     * Select the transfer tier for a payload.
     * - Files under 500 KB always use Tier 2 (RFCOMM).
     * - Files >= 500 KB use Tier 3 when a shared network exists, else fall back to Tier 2.
     */
    fun decide(payloadSize: Long, sharedNetworkAvailable: Boolean): TierDecision {
        if (payloadSize < THRESHOLD_BYTES) {
            return TierDecision(TransferTier.BLUETOOTH, isSlowFallback = false)
        }
        return if (sharedNetworkAvailable) {
            TierDecision(TransferTier.WIFI_LAN, isSlowFallback = false)
        } else {
            TierDecision(TransferTier.BLUETOOTH, isSlowFallback = true)
        }
    }
}
