import SwiftUI

@main
struct BridgeApp: App {
    @StateObject private var bleManager = BLEManager()
    @StateObject private var batteryState = PhoneBatteryState()

    init() {
        NotificationManager.shared.requestPermission()
    }

    var body: some Scene {
        MenuBarExtra {
            StatusPanel(bleManager: bleManager, batteryState: batteryState)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: menuBarIcon)
                if bleManager.currentLinkState == .connected,
                   let level = batteryState.level {
                    Image(systemName: batteryState.sfSymbol)
                        .foregroundStyle(batteryState.tintColor)
                    Text("\(level)%")
                        .font(.caption)
                }
            }
        }
    }

    private var menuBarIcon: String {
        switch bleManager.currentLinkState {
        case .connected:
            return "link.circle.fill"
        case .connecting:
            return "antenna.radiowaves.left.and.right"
        case .disconnected:
            return "link.circle"
        case .bluetoothOff:
            return "xmark.circle"
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

    var sfSymbol: String {
        guard let level else { return "battery.0" }
        let base: String
        switch level {
        case 76...100: base = "battery.100"
        case 51...75: base = "battery.75"
        case 26...50: base = "battery.50"
        case 11...25: base = "battery.25"
        default: base = "battery.0"
        }
        return isCharging ? "\(base).bolt" : base
    }

    var tintColor: Color {
        if let level, level <= 15, !isCharging {
            return .red
        }
        return .primary
    }
}
