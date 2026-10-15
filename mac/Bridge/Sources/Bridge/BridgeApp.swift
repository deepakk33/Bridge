import SwiftUI

@main
struct BridgeApp: App {
    @StateObject private var bleManager = BLEManager()

    var body: some Scene {
        MenuBarExtra {
            BridgeMenu(bleManager: bleManager)
        } label: {
            Label("Bridge", systemImage: menuBarIcon)
        }
    }

    private var menuBarIcon: String {
        switch bleManager.connectionState {
        case .connected:
            return "link.circle.fill"
        case .scanning, .connecting:
            return "antenna.radiowaves.left.and.right"
        case .disconnected:
            return "link.circle"
        case .bluetoothOff:
            return "xmark.circle"
        }
    }
}

// MARK: - Menu view

struct BridgeMenu: View {
    @ObservedObject var bleManager: BLEManager

    var body: some View {
        VStack {
            statusSection
            Divider()
            actionSection
        }
    }

    @ViewBuilder
    private var statusSection: some View {
        Text("Bridge")
            .font(.headline)

        switch bleManager.connectionState {
        case .connected:
            if let name = bleManager.connectedDeviceName {
                Text("Connected to \(name)")
                    .foregroundStyle(.secondary)
            } else {
                Text("Connected")
                    .foregroundStyle(.secondary)
            }
        case .scanning:
            Text("Scanning...")
                .foregroundStyle(.secondary)
        case .connecting:
            Text("Connecting...")
                .foregroundStyle(.secondary)
        case .disconnected:
            Text("Disconnected")
                .foregroundStyle(.secondary)
        case .bluetoothOff:
            Text("Bluetooth Off")
                .foregroundStyle(.red)
        }
    }

    @ViewBuilder
    private var actionSection: some View {
        if bleManager.connectionState == .connected {
            Button("Disconnect") {
                bleManager.disconnect()
            }
        }

        Button("Quit Bridge") {
            NSApplication.shared.terminate(nil)
        }
        .keyboardShortcut("q")
    }
}
