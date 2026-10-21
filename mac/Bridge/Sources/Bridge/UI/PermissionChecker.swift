import Foundation
@preconcurrency import CoreBluetooth
import UserNotifications
import Network
import os

/// Grant status for a single TCC permission.
enum PermissionStatus: String, Sendable {
    case granted
    case denied
    case notRequested
    case restricted

    var label: String {
        switch self {
        case .granted: return "Granted"
        case .denied: return "Denied"
        case .notRequested: return "Not Requested"
        case .restricted: return "Restricted"
        }
    }
}

/// One row in the permission panel.
struct PermissionItem: Identifiable, Sendable {
    let id: String
    let name: String
    let status: PermissionStatus
    /// System Settings URL to open for a Fix/Request action.
    let settingsURL: String?
}

/// Checks and tracks every TCC permission Bridge needs: Bluetooth, Notifications,
/// Local Network. Checked at launch and surfaced in the menu bar panel so a
/// revocation (e.g. after a macOS point release) is caught instead of failing
/// silently.
@MainActor
final class PermissionChecker: ObservableObject {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "PermissionChecker")

    @Published private(set) var items: [PermissionItem] = []
    @Published private(set) var localNetworkStatus: PermissionStatus = .notRequested

    /// True when at least one required permission is not granted (drives the badge).
    var hasDeniedPermission: Bool {
        items.contains { $0.status == .denied || $0.status == .restricted }
    }

    /// Re-query all permission states.
    func refresh() {
        var next: [PermissionItem] = []

        next.append(PermissionItem(
            id: "bluetooth",
            name: "Bluetooth",
            status: Self.map(CBManager.authorization),
            settingsURL: "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth"
        ))

        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            let status = Self.mapNotification(settings.authorizationStatus)
            Task { @MainActor in
                self?.updateNotification(status)
            }
        }

        next.append(PermissionItem(
            id: "localNetwork",
            name: "Local Network",
            status: localNetworkStatus,
            settingsURL: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork"
        ))

        items = next
        Self.logger.info("Permission refresh: \(next.count) items, denied=\(self.hasDeniedPermission)")
    }

    private func updateNotification(_ status: PermissionStatus) {
        let item = PermissionItem(
            id: "notifications",
            name: "Notifications",
            status: status,
            settingsURL: "x-apple.systempreferences:com.apple.preference.notifications"
        )
        if let idx = items.firstIndex(where: { $0.id == "notifications" }) {
            items[idx] = item
        } else {
            items.append(item)
        }
    }

    func setLocalNetwork(_ status: PermissionStatus) {
        localNetworkStatus = status
        if let idx = items.firstIndex(where: { $0.id == "localNetwork" }) {
            items[idx] = PermissionItem(
                id: "localNetwork",
                name: "Local Network",
                status: status,
                settingsURL: items[idx].settingsURL
            )
        }
    }

    // MARK: - Pure mappings (testable)

    nonisolated static func map(_ auth: CBManagerAuthorization) -> PermissionStatus {
        switch auth {
        case .allowedAlways: return .granted
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notRequested
        @unknown default: return .notRequested
        }
    }

    nonisolated static func mapNotification(_ status: UNAuthorizationStatus) -> PermissionStatus {
        switch status {
        case .authorized, .provisional, .ephemeral: return .granted
        case .denied: return .denied
        case .notDetermined: return .notRequested
        @unknown default: return .notRequested
        }
    }
}
