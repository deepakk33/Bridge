import AppKit
import os

/// Keeps the Mac app to one process.
///
/// Two instances share one Core Bluetooth central: when the second quits it sends `stop scan`
/// with `updateScanParams:YES` to `bluetoothd`, which stops scanning for the *survivor*, and the
/// survivor then sits on "Out of range" until it is restarted. The check reads the live process
/// list rather than a lock file, so a crash leaves nothing behind to clear.
enum SingleInstanceGuard {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "SingleInstanceGuard")

    struct Instance: Sendable {
        let pid: pid_t
        let bundleIdentifier: String?
    }

    /// The instance already holding the app, or nil to carry on launching. Matched on bundle
    /// identifier alone — two builds at different paths is exactly the case this exists for.
    static func holder(among running: [Instance], selfPid: pid_t, bundleIdentifier: String?) -> Instance? {
        guard let bundleIdentifier else { return nil }
        return running.first { $0.pid != selfPid && $0.bundleIdentifier == bundleIdentifier }
    }

    static func isUnderTest(_ environment: [String: String]) -> Bool {
        environment["XCTestConfigurationFilePath"] != nil
    }

    /// Call first in `BridgeApp.init()`, before anything creates a central. Does not return when
    /// another instance holds the app.
    @MainActor
    static func enforce() {
        // BridgeTests runs hosted in Bridge.app; the guard would terminate its own test host.
        guard !isUnderTest(ProcessInfo.processInfo.environment) else { return }

        let current = NSRunningApplication.current
        let running = NSWorkspace.shared.runningApplications.map {
            Instance(pid: $0.processIdentifier, bundleIdentifier: $0.bundleIdentifier)
        }
        guard let other = holder(
            among: running,
            selfPid: current.processIdentifier,
            bundleIdentifier: current.bundleIdentifier
        ) else { return }

        logger.notice("Bridge is already running (pid \(other.pid, privacy: .public)); handing over and exiting")
        NSRunningApplication(processIdentifier: other.pid)?.activate()
        exit(0)
    }
}
