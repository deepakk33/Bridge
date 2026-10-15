import Foundation
import os

enum LinkState: String, Sendable {
    case disconnected
    case connecting
    case connected
    case bluetoothOff
}

@MainActor
final class LinkStateMachine: ObservableObject {
    @Published private(set) var state: LinkState = .disconnected
    @Published private(set) var lastSeen: Date?

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "LinkStateMachine")

    func transition(to newState: LinkState) {
        let oldState = state
        guard oldState != newState else { return }
        state = newState

        if newState == .connecting && oldState == .connected {
            lastSeen = Date()
        }
        if newState == .connected {
            lastSeen = nil
        }

        Self.logger.info("State: \(oldState.rawValue) → \(newState.rawValue)")
    }

    func recordLastSeen(_ date: Date = Date()) {
        lastSeen = date
    }

    var lastSeenText: String? {
        guard let lastSeen, state != .connected else { return nil }
        let interval = Date().timeIntervalSince(lastSeen)
        if interval < 60 {
            return "Last seen: just now"
        } else if interval < 3600 {
            let minutes = Int(interval / 60)
            return "Last seen: \(minutes) min ago"
        } else {
            let hours = Int(interval / 3600)
            return "Last seen: \(hours) hr ago"
        }
    }
}
