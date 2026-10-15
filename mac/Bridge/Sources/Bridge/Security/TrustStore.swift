import Foundation
import os

final class TrustStore: @unchecked Sendable {
    static let shared = TrustStore()

    private static let logger = Logger(subsystem: "com.bridge.mac", category: "TrustStore")
    private static let defaultsKey = "com.bridge.mac.trustedDevices"

    private let lock = NSLock()
    private var trustedDevices: [String: String] = [:]

    init() {
        if let stored = UserDefaults.standard.dictionary(forKey: Self.defaultsKey) as? [String: String] {
            trustedDevices = stored
        }
    }

    func pin(deviceId: String, fingerprint: String) {
        lock.withLock {
            trustedDevices[deviceId] = fingerprint
            save()
        }
        Self.logger.info("Pinned fingerprint for device \(deviceId)")
    }

    func unpin(deviceId: String) {
        lock.withLock {
            trustedDevices.removeValue(forKey: deviceId)
            save()
        }
        Self.logger.info("Unpinned device \(deviceId)")
    }

    func unpinAll() {
        lock.withLock {
            trustedDevices.removeAll()
            save()
        }
        Self.logger.info("Unpinned all devices")
    }

    func isTrusted(deviceId: String, fingerprint: String) -> Bool {
        lock.withLock { trustedDevices[deviceId] == fingerprint }
    }

    func hasPinnedDevice(deviceId: String) -> Bool {
        lock.withLock { trustedDevices[deviceId] != nil }
    }

    func pinnedFingerprint(for deviceId: String) -> String? {
        lock.withLock { trustedDevices[deviceId] }
    }

    func pairedDeviceId() -> String? {
        lock.withLock { trustedDevices.keys.first }
    }

    var isEmpty: Bool {
        lock.withLock { trustedDevices.isEmpty }
    }

    private func save() {
        UserDefaults.standard.set(trustedDevices, forKey: Self.defaultsKey)
    }
}
