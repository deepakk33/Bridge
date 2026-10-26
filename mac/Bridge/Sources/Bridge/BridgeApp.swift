import SwiftUI

@main
struct BridgeApp: App {
    @StateObject private var bleManager: BLEManager
    @StateObject private var batteryState = PhoneBatteryState()
    @StateObject private var permissions = PermissionChecker()
    @StateObject private var fileTransfer: FileTransferManager

    init() {
        NotificationManager.shared.requestPermission()
        let manager = BLEManager()
        _bleManager = StateObject(wrappedValue: manager)
        _fileTransfer = StateObject(wrappedValue: FileTransferManager(sendPacket: { packet in
            _ = manager.sendPacket(packet)
        }))
    }

    var body: some Scene {
        MenuBarExtra {
            StatusPanel(
                bleManager: bleManager,
                batteryState: batteryState,
                permissions: permissions,
                fileTransfer: fileTransfer
            )
            .onAppear { permissions.refresh() }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: menuBarIcon)
                if bleManager.pairingManager.incomingPairRequest != nil {
                    Image(systemName: "person.badge.key.fill")
                        .foregroundStyle(.orange)
                }
                if permissions.hasDeniedPermission {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.yellow)
                }
                if bleManager.linkHealth == .connected,
                   let level = batteryState.level {
                    Image(systemName: batteryState.sfSymbol)
                        .foregroundStyle(batteryState.tintColor)
                    Text("\(level)%")
                        .font(.caption)
                }
            }
        }
        .menuBarExtraStyle(.window)
    }

    private var menuBarIcon: String {
        switch bleManager.linkHealth {
        case .connected:
            return "link.circle.fill"
        case .verifying:
            return "ellipsis.circle"
        case .stale:
            return "exclamationmark.circle"
        case .dead:
            return "exclamationmark.triangle.fill"
        case .outOfRange:
            return "antenna.radiowaves.left.and.right"
        case .bluetoothOff:
            return "xmark.circle"
        case .disconnected:
            return "link.circle"
        }
    }
}

@MainActor
final class PhoneBatteryState: ObservableObject {
    @Published var level: Int?
    @Published var isCharging: Bool = false

    private var observer: NSObjectProtocol?

    init() {
        observer = NotificationCenter.default.addObserver(
            forName: .bridgeBatteryUpdated,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let info = notification.userInfo,
                  let charge = info["currentCharge"] as? Int,
                  let charging = info["isCharging"] as? Bool else { return }
            self?.level = charge
            self?.isCharging = charging
        }

        NotificationCenter.default.addObserver(
            forName: .bridgeDisconnected,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.level = nil
            self?.isCharging = false
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    /// SF Symbols ships a charging variant only for the full glyph (`battery.100.bolt`), so
    /// appending `.bolt` to a partial level resolved to nothing and drew a blank gap while the
    /// phone was charging. The level symbol stands alone; charging is stated in the label.
    var sfSymbol: String {
        guard let level else { return "battery.0percent" }
        switch level {
        case 76...100: return "battery.100percent"
        case 51...75: return "battery.75percent"
        case 26...50: return "battery.50percent"
        case 11...25: return "battery.25percent"
        default: return "battery.0percent"
        }
    }

    var tintColor: Color {
        Self.tintColor(level: level, isCharging: isCharging)
    }

    nonisolated static func tintColor(level: Int?, isCharging: Bool) -> Color {
        if let level, level <= 15, !isCharging {
            return .red
        }
        return .primary
    }
}
