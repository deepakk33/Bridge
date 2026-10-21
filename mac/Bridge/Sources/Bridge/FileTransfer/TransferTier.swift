import Foundation

/// Transfer tier for a file payload.
enum TransferTier: String, Sendable {
    /// Tier 2 — Bluetooth Classic RFCOMM. No network required.
    case bluetooth
    /// Tier 3 — Wi-Fi LAN over mDNS + TCP. Full LAN throughput.
    case wifiLan
}

/// Result of tier selection, including whether a slow-transfer fallback occurred.
struct TierDecision: Equatable, Sendable {
    let tier: TransferTier
    /// True when a large file fell back to Tier 2 because no shared network was available.
    let isSlowFallback: Bool
}

enum TierRouter {
    /// Files below this size route to Tier 2; at or above, to Tier 3 when possible.
    static let thresholdBytes: Int64 = 512_000

    /// Select the transfer tier for a payload.
    /// - Files under 500 KB always use Tier 2 (RFCOMM).
    /// - Files >= 500 KB use Tier 3 when a shared network exists, else fall back to Tier 2.
    static func decide(payloadSize: Int64, sharedNetworkAvailable: Bool) -> TierDecision {
        if payloadSize < thresholdBytes {
            return TierDecision(tier: .bluetooth, isSlowFallback: false)
        }
        if sharedNetworkAvailable {
            return TierDecision(tier: .wifiLan, isSlowFallback: false)
        }
        return TierDecision(tier: .bluetooth, isSlowFallback: true)
    }
}
