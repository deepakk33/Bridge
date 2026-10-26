import Foundation

/// Richer link-health classification for the watchdog and menu bar, layered on
/// top of the raw `LinkState`. Distinguishes a clean out-of-range disconnect from
/// a silent death (BLE still connected but no packets arriving — e.g. the Android
/// service was killed by One UI battery management).
enum LinkHealth: Equatable, Sendable {
    /// Packets flowing normally from a verified peer.
    case connected
    /// A channel is open but the peer is not yet verified against the trust store.
    case verifying
    /// No packet for 60–90s. Warning state, link not yet declared dead.
    case stale(seconds: Int)
    /// No packet for >=90s while BLE is still connected, or the peer tore the
    /// connection down from its side: the phone's service is gone, not the phone.
    case dead
    /// BLE disconnected cleanly (phone walked away).
    case outOfRange
    /// Bluetooth is turned off on the Mac.
    case bluetoothOff
    /// The link was closed from this side and is not being retried.
    case disconnected
}

/// Why the BLE peripheral connection ended. A force-stopped Android app stops
/// advertising exactly as a departed phone does, so the disconnect reason Core
/// Bluetooth reports is the only signal that tells the two apart.
enum DisconnectReason: Equatable, Sendable {
    /// We cancelled the connection ourselves.
    case local
    /// Supervision timeout — the link faded, i.e. the phone moved away.
    case supervisionTimeout
    /// The remote stack tore the connection down — app force-stopped, phone Bluetooth off.
    case peerTerminated
    /// Anything else, including a disconnect with no error attached.
    case unknown
}

enum LinkHealthEvaluator {
    /// Heartbeat interval; a ping is sent after this much idle time.
    static let heartbeatInterval: TimeInterval = 30
    /// Stale (yellow warning) after this much silence.
    static let staleThreshold: TimeInterval = 60
    /// Declared dead after this much silence (3x heartbeat).
    static let deadThreshold: TimeInterval = 90

    /// Classify the link from the last packet time and transport state.
    static func evaluate(
        lastSeen: Date?,
        now: Date = Date(),
        bleConnected: Bool,
        bluetoothOn: Bool,
        isVerified: Bool = true,
        lastDisconnect: DisconnectReason? = nil
    ) -> LinkHealth {
        if !bluetoothOn { return .bluetoothOff }
        if !bleConnected {
            switch lastDisconnect {
            case .local: return .disconnected
            case .peerTerminated: return .dead
            case .supervisionTimeout, .unknown, nil: return .outOfRange
            }
        }
        // Silence outranks verification: a peer that opened a socket and then went quiet
        // is a dead service, not a handshake in progress.
        guard let lastSeen else { return isVerified ? .connected : .verifying }
        let elapsed = now.timeIntervalSince(lastSeen)
        if elapsed >= deadThreshold { return .dead }
        if elapsed >= staleThreshold { return .stale(seconds: Int(elapsed)) }
        return isVerified ? .connected : .verifying
    }

    /// "Last seen: just now" / "Xs ago" / "Xm ago" per spec.
    static func lastSeenText(lastSeen: Date?, now: Date = Date()) -> String {
        guard let lastSeen else { return "Last seen: just now" }
        let elapsed = Int(now.timeIntervalSince(lastSeen))
        if elapsed < 5 { return "Last seen: just now" }
        if elapsed < 60 { return "Last seen: \(elapsed)s ago" }
        return "Last seen: \(elapsed / 60)m ago"
    }

    /// Actionable guidance for a problem state — what to do, not just what broke.
    static func guidance(for health: LinkHealth) -> String? {
        switch health {
        case .dead:
            return "The phone's Bridge service may have been stopped by battery management. Open Bridge on the phone to restart."
        case .bluetoothOff:
            return "Bluetooth is off. Open Bluetooth settings to re-enable."
        case .outOfRange:
            return "Phone is out of range. It will reconnect automatically when back in range."
        case .stale, .connected, .verifying, .disconnected:
            return nil
        }
    }
}
