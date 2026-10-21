import Foundation

/// Richer link-health classification for the watchdog and menu bar, layered on
/// top of the raw `LinkState`. Distinguishes a clean out-of-range disconnect from
/// a silent death (BLE still connected but no packets arriving — e.g. the Android
/// service was killed by One UI battery management).
enum LinkHealth: Equatable, Sendable {
    /// Packets flowing normally.
    case connected
    /// No packet for 60–90s. Warning state, link not yet declared dead.
    case stale(seconds: Int)
    /// No packet for >=90s while BLE is still connected: silent service death.
    case dead
    /// BLE disconnected cleanly (phone walked away).
    case outOfRange
    /// Bluetooth is turned off on the Mac.
    case bluetoothOff
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
        bluetoothOn: Bool
    ) -> LinkHealth {
        if !bluetoothOn { return .bluetoothOff }
        if !bleConnected { return .outOfRange }
        guard let lastSeen else { return .connected }
        let elapsed = now.timeIntervalSince(lastSeen)
        if elapsed >= deadThreshold { return .dead }
        if elapsed >= staleThreshold { return .stale(seconds: Int(elapsed)) }
        return .connected
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
        case .stale:
            return nil
        case .connected:
            return nil
        }
    }
}
