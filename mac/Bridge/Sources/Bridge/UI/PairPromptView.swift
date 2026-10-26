import SwiftUI

/// The pair confirmation, rendered inline at the top of the status panel rather than as a
/// sheet: the window-style menu bar panel is non-activating, and a sheet attached to one
/// cannot be relied upon to present.
struct PairPromptView: View {
    let request: PairingManager.PairRequestInfo
    let onAccept: () -> Void
    let onReject: () -> Void

    @State private var timeRemaining = 30
    @State private var timer: Timer?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Pair Request", systemImage: "link.badge.plus")
                .font(.subheadline.bold())
                .foregroundStyle(.blue)

            Text("\(request.deviceName) wants to pair")
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.tail)

            Text(formatFingerprint(request.fingerprint))
                .font(.system(.caption2, design: .monospaced))
                // A SHA-256 fingerprint formats to 95 characters and needs three lines here.
                // Truncating it would hide exactly the span where a mismatch shows up, and
                // lineLimit alone is a maximum — without fixedSize the text stays on one line.
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
                .padding(6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.secondary.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 6))

            Text("Auto-reject in \(timeRemaining)s")
                .font(.caption2)
                .foregroundStyle(.secondary)

            HStack(spacing: 8) {
                // No keyboard shortcuts: inline in the panel, Escape is the dismiss key and
                // Return the default action, so either would pair or refuse a peer by accident.
                Button("Reject") {
                    cleanup()
                    onReject()
                }
                .accessibilityLabel("Reject pair request from \(request.deviceName)")

                Button("Accept") {
                    cleanup()
                    onAccept()
                }
                .buttonStyle(.borderedProminent)
                .accessibilityLabel("Accept pair request from \(request.deviceName)")
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .onAppear {
            // SwiftUI may reuse this view for a later request, so restart rather than resume.
            timeRemaining = 30
            timer?.invalidate()
            timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
                timeRemaining -= 1
                if timeRemaining <= 0 {
                    cleanup()
                    onReject()
                }
            }
        }
        .onDisappear {
            cleanup()
        }
    }

    private func cleanup() {
        timer?.invalidate()
        timer = nil
    }

    private func formatFingerprint(_ hex: String) -> String {
        stride(from: 0, to: hex.count, by: 2).map { i in
            let start = hex.index(hex.startIndex, offsetBy: i)
            let end = hex.index(start, offsetBy: min(2, hex.count - i))
            return String(hex[start..<end]).uppercased()
        }.joined(separator: ":")
    }
}
