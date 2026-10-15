import SwiftUI

struct StatusPanel: View {
    @ObservedObject var bleManager: BLEManager

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            headerSection
            Divider()
            statusSection
            Divider()
            actionSection
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
        switch bleManager.linkState.state {
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
    }

    @ViewBuilder
    private var actionSection: some View {
        if case .paired = bleManager.pairingManager.state {
            Button("Unpair") {
                bleManager.pairingManager.unpair()
            }
        }

        if case .unpaired = bleManager.pairingManager.state,
           bleManager.linkState.state == .connected {
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
