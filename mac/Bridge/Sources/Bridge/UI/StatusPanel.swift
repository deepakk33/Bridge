import SwiftUI

struct StatusPanel: View {
    @ObservedObject var bleManager: BLEManager
    @ObservedObject var batteryState: PhoneBatteryState
    @ObservedObject var permissions: PermissionChecker
    @ObservedObject var fileTransfer: FileTransferManager

    /// A working link, not merely an open socket. Everything that shows phone data or
    /// sends to the phone hangs off this.
    private var isConnected: Bool {
        bleManager.linkHealth == .connected
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            pairSection
            headerSection
            statusSection
            Divider()
            transferSection
            permissionSection
            Divider()
            actionSection
        }
        .frame(width: 260)
        .padding(12)
    }

    /// Rendered above everything else so a pending request is the first thing the panel
    /// shows. A sheet cannot be relied upon from the non-activating window-style panel.
    @ViewBuilder
    private var pairSection: some View {
        if let request = bleManager.pairingManager.incomingPairRequest {
            PairPromptView(
                request: request,
                onAccept: { bleManager.pairingManager.acceptIncomingPair() },
                onReject: { bleManager.pairingManager.rejectIncomingPair() }
            )
            // A replacement request reuses this view, so tie identity to the request or the
            // second one inherits the first's countdown.
            .id(request.id)
        }
    }

    @ViewBuilder
    private var headerSection: some View {
        Text("Bridge")
            .font(.headline)
    }

    @ViewBuilder
    private var statusSection: some View {
        switch bleManager.linkHealth {
        case .connected:
            if let name = bleManager.connectedDeviceName {
                Label(name, systemImage: "link.circle.fill")
                    .foregroundStyle(.green)
                    .lineLimit(1)
                    .truncationMode(.tail)
            } else {
                Label("Connected", systemImage: "link.circle.fill")
                    .foregroundStyle(.green)
            }

        case .verifying:
            Label("Verifying…", systemImage: "ellipsis.circle")
                .foregroundStyle(.secondary)

        case .stale:
            Label("Waiting for the phone", systemImage: "exclamationmark.circle")
                .foregroundStyle(.orange)

        case .dead:
            Label("Not responding", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)

        case .searching:
            Label("Searching…", systemImage: "dot.radiowaves.left.and.right")
                .foregroundStyle(.secondary)

        case .outOfRange:
            Label("Out of range", systemImage: "antenna.radiowaves.left.and.right")
                .foregroundStyle(.secondary)

        case .bluetoothOff:
            Label("Bluetooth off", systemImage: "xmark.circle")
                .foregroundStyle(.red)

        case .disconnected:
            Label("Disconnected", systemImage: "link.circle")
                .foregroundStyle(.secondary)
        }

        if let text = bleManager.lastSeenText, showsLastSeen {
            Text(text)
                .font(.caption)
                .foregroundStyle(.tertiary)
        }

        if let guidance = LinkHealthEvaluator.guidance(for: bleManager.linkHealth) {
            Text(guidance)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }

        if case .paired(_, let name) = bleManager.pairingManager.state {
            Text("Paired with \(name)")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.tail)
        }

        if isConnected {
            Text(bleManager.mirroringState?.summary ?? MirroringState.unknownSummary)
                .font(.caption)
                .foregroundStyle(mirroringTint)
                .lineLimit(1)
        }

        if isConnected, let level = batteryState.level {
            HStack(spacing: 4) {
                Image(systemName: batteryState.sfSymbol)
                    .foregroundStyle(batteryState.tintColor)
                Text(batteryState.isCharging ? "Phone: \(level)% · Charging" : "Phone: \(level)%")
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
        .buttonStyle(.borderedProminent)
        .disabled(!isConnected)
        // The window-style panel does not expose button titles to VoiceOver on its own.
        .accessibilityLabel("Send File")

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

    }

    /// Granted permissions are omitted, so the section's presence is itself the signal.
    @ViewBuilder
    private var permissionSection: some View {
        let attention = permissions.needsAttention
        if !attention.isEmpty {
            Text("Permissions")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            ForEach(attention) { item in
                HStack(spacing: 6) {
                    Circle()
                        .fill(color(for: item.status))
                        .frame(width: 8, height: 8)
                    Text(item.name)
                        .font(.caption)
                        .lineLimit(1)
                    Text(item.status.label)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    if let urlString = item.settingsURL, let url = URL(string: urlString) {
                        Button(item.status == .notRequested ? "Request" : "Fix") {
                            NSWorkspace.shared.open(url)
                        }
                        .font(.caption2)
                        .accessibilityLabel(
                            item.status == .notRequested
                                ? "Request \(item.name) permission"
                                : "Fix \(item.name) permission"
                        )
                    }
                }
            }
        }
    }

    /// Only states where a previous packet time is the useful part of the story —
    /// "Verifying" and "Disconnected" are about this moment, not about a gap.
    private var showsLastSeen: Bool {
        switch bleManager.linkHealth {
        case .stale, .dead, .outOfRange: return true
        case .connected, .verifying, .bluetoothOff, .disconnected, .searching: return false
        }
    }

    /// Amber only when nothing is reaching the Mac — the state the user cannot
    /// otherwise tell apart from a quiet phone.
    private var mirroringTint: Color {
        guard let state = bleManager.mirroringState else { return .secondary }
        return (state.muted || state.allowed == 0) ? .orange : .secondary
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
            .accessibilityLabel("Unpair")
        }

        if case .unpaired = bleManager.pairingManager.state {
            Button("Pair") {
                bleManager.pairingManager.requestPairing()
            }
            .accessibilityLabel("Pair")
        }

        Button("Quit Bridge") {
            NSApplication.shared.terminate(nil)
        }
        .keyboardShortcut("q")
        .accessibilityLabel("Quit Bridge")
    }
}
