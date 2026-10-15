import SwiftUI

@main
struct BridgeApp: App {
    @StateObject private var bleManager = BLEManager()

    var body: some Scene {
        MenuBarExtra {
            StatusPanel(bleManager: bleManager)
        } label: {
            Label("Bridge", systemImage: menuBarIcon)
        }
    }

    private var menuBarIcon: String {
        switch bleManager.linkState.state {
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
