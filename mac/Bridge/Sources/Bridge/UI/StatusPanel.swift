import SwiftUI
import UniformTypeIdentifiers

struct StatusPanel: View {
    @ObservedObject var bleManager: BLEManager
    @ObservedObject var batteryState: PhoneBatteryState
    @ObservedObject var permissions: PermissionChecker
    @ObservedObject var fileTransfer: FileTransferManager

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            headerSection
            Divider()
            statusSection
            Divider()
            transferSection
            Divider()
            permissionSection
            Divider()
            actionSection
        }
        .dropDestination(for: URL.self) { urls, _ in
            guard let url = urls.first else { return false }
            fileTransfer.sendFile(at: url, sharedNetworkAvailable: true)
            return true
        }
        .sheet(item: Binding(
            get: { bleManager.pairingManager.incomingPairRequest },
            set: { bleManager.pairingManager.incomingPairRequest = $0 }
        )) { request in
            PairPromptView(
                request: request,
                onAccept: { bleManager.pairingManager.acceptIncomingPair() },
                onReject: { bleManager.pairingManager.rejectIncomingPair() }
            )
        }
    }

    @ViewBuilder
    private var headerSection: some View {
        Text("Bridge")
            .font(.headline)
    }

    @ViewBuilder
    private var statusSection: some View {
        switch bleManager.currentLinkState {
        case .connected:
            if let name = bleManager.connectedDeviceName {
                Label(name, systemImage: "link.circle.fill")
                    .foregroundStyle(.green)
            } else {
                Label("Connected", systemImage: "link.circle.fill")
                    .foregroundStyle(.green)
            }

        case .connecting:
            Label("Out of range", systemImage: "antenna.radiowaves.left.and.right")
                .foregroundStyle(.secondary)
            if let text = bleManager.linkState.lastSeenText {
                Text(text)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }

        case .disconnected:
            Label("Disconnected", systemImage: "link.circle")
                .foregroundStyle(.secondary)

        case .bluetoothOff:
            Label("Bluetooth off", systemImage: "xmark.circle")
                .foregroundStyle(.red)
        }

        if case .paired(_, let name) = bleManager.pairingManager.state {
            Text("Paired with \(name)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }

        if bleManager.currentLinkState == .connected, let level = batteryState.level {
            HStack(spacing: 4) {
                Image(systemName: batteryState.sfSymbol)
                    .foregroundStyle(batteryState.tintColor)
                Text("Phone: \(level)%")
                    .font(.caption)
            }
            .foregroundStyle(batteryState.tintColor)
        }
    }

    @ViewBuilder
    private var transferSection: some View {
        Button("Send File…") {
            let panel = NSOpenPanel()
            panel.canChooseFiles = true
            panel.canChooseDirectories = false
            panel.allowsMultipleSelection = false
            if panel.runModal() == .OK, let url = panel.url {
                fileTransfer.sendFile(at: url, sharedNetworkAvailable: true)
            }
        }
        .disabled(bleManager.currentLinkState != .connected)

        switch fileTransfer.state {
        case .idle:
            EmptyView()
        case .preparing(let name):
            Label("Preparing \(name)…", systemImage: "hourglass")
                .font(.caption)
        case .inProgress(let name, let tier):
            Label("Sending \(name) over \(tier == .wifiLan ? "Wi-Fi" : "Bluetooth")…", systemImage: "arrow.up.circle")
                .font(.caption)
        case .slowFallbackWarning(let name):
            Label("No shared Wi-Fi — \(name) will send slowly over Bluetooth", systemImage: "exclamationmark.triangle")
                .font(.caption)
                .foregroundStyle(.orange)
        case .completed(let name):
            Label("Sent \(name)", systemImage: "checkmark.circle")
                .font(.caption)
                .foregroundStyle(.green)
        case .failed(let name, let reason):
            Label("Failed \(name): \(reason)", systemImage: "xmark.circle")
                .font(.caption)
                .foregroundStyle(.red)
        }

        Text("Drag a file onto this menu to send it to your phone.")
            .font(.caption2)
            .foregroundStyle(.tertiary)
    }

    @ViewBuilder
    private var permissionSection: some View {
        Text("Permissions")
            .font(.caption)
            .foregroundStyle(.secondary)
        ForEach(permissions.items) { item in
            HStack(spacing: 6) {
                Circle()
                    .fill(color(for: item.status))
                    .frame(width: 8, height: 8)
                Text(item.name)
                    .font(.caption)
                Spacer()
                Text(item.status.label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if item.status != .granted, let urlString = item.settingsURL, let url = URL(string: urlString) {
                    Button(item.status == .notRequested ? "Request" : "Fix") {
                        NSWorkspace.shared.open(url)
                    }
                    .font(.caption2)
                }
            }
        }
    }

    private func color(for status: PermissionStatus) -> Color {
        switch status {
        case .granted: return .green
        case .denied, .restricted: return .red
        case .notRequested: return .yellow
        }
    }

    @ViewBuilder
    private var actionSection: some View {
        if case .paired = bleManager.pairingManager.state {
            Button("Unpair") {
                bleManager.pairingManager.unpair()
            }
        }

        if case .unpaired = bleManager.pairingManager.state {
            Button("Pair") {
                bleManager.pairingManager.requestPairing()
            }
        }

        Button("Quit Bridge") {
            NSApplication.shared.terminate(nil)
        }
        .keyboardShortcut("q")
    }
}
