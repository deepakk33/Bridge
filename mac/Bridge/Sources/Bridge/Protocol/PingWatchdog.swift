import Foundation
import os

@MainActor
final class PingWatchdog {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PingWatchdog")

    let pingInterval: TimeInterval
    let watchdogTimeout: TimeInterval

    private var pingTimer: Timer?
    private var watchdogTimer: Timer?
    private var lastPacketReceived: Date?
    private var sendPing: (() -> Void)?
    private var onTimeout: (() -> Void)?

    init(pingInterval: TimeInterval = 30, watchdogTimeout: TimeInterval = 90) {
        self.pingInterval = pingInterval
        self.watchdogTimeout = watchdogTimeout
    }

    func start(sendPing: @escaping () -> Void, onTimeout: @escaping () -> Void) {
        self.sendPing = sendPing
        self.onTimeout = onTimeout
        lastPacketReceived = Date()

        pingTimer = Timer.scheduledTimer(withTimeInterval: pingInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.firePing()
            }
        }

        watchdogTimer = Timer.scheduledTimer(withTimeInterval: pingInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.checkWatchdog()
            }
        }

        Self.logger.info("Watchdog started (ping=\(self.pingInterval)s, timeout=\(self.watchdogTimeout)s)")
    }

    func stop() {
        pingTimer?.invalidate()
        pingTimer = nil
        watchdogTimer?.invalidate()
        watchdogTimer = nil
        sendPing = nil
        onTimeout = nil
        Self.logger.info("Watchdog stopped")
    }

    func packetReceived() {
        lastPacketReceived = Date()
    }

    var lastSeen: Date? {
        lastPacketReceived
    }

    private func firePing() {
        sendPing?()
    }

    private func checkWatchdog() {
        guard let last = lastPacketReceived else { return }
        let elapsed = Date().timeIntervalSince(last)
        if elapsed > watchdogTimeout {
            Self.logger.warning("Watchdog timeout: no packet for \(String(format: "%.0f", elapsed))s")
            // stop() clears onTimeout, so hold the callback before tearing down.
            let callback = onTimeout
            stop()
            callback?()
        }
    }
}
