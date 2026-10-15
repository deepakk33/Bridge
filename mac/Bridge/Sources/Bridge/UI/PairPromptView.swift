import SwiftUI

struct PairPromptView: View {
    let request: PairingManager.PairRequestInfo
    let onAccept: () -> Void
    let onReject: () -> Void

    @State private var timeRemaining = 30
    @State private var timer: Timer?

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "link.badge.plus")
                .font(.system(size: 40))
                .foregroundStyle(.blue)

            Text("Pair Request")
                .font(.title2.bold())

            Text("\(request.deviceName) wants to pair")
                .font(.body)

            VStack(alignment: .leading, spacing: 4) {
                Text("Certificate fingerprint:")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(formatFingerprint(request.fingerprint))
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
            }
            .padding(8)
            .background(Color.secondary.opacity(0.1))
            .cornerRadius(8)

            Text("Auto-reject in \(timeRemaining)s")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(spacing: 16) {
                Button("Reject") {
                    cleanup()
                    onReject()
                }
                .keyboardShortcut(.cancelAction)

                Button("Accept") {
                    cleanup()
                    onAccept()
                }
                .keyboardShortcut(.defaultAction)
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .frame(width: 340)
        .onAppear {
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
