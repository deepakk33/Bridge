import SwiftUI

/// The pair confirmation, rendered inline at the top of the status panel rather than as a
/// sheet: the window-style menu bar panel is non-activating, and a sheet attached to one
/// cannot be relied upon to present.
///
/// The six digits are the control. They are derived from both certificate fingerprints, so
/// they agree only when each device is really talking to the other; the full fingerprint
/// stays available underneath for anyone who wants to read it.
struct PairPromptView: View {
    let request: PairingManager.PairRequestInfo
    let onAccept: () -> Void
    let onReject: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Pair Request", systemImage: "link.badge.plus")
                .font(.subheadline.bold())
                .foregroundStyle(.blue)

            Text("\(request.deviceName) wants to pair")
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.tail)

            Text(spacedCode)
                .font(.system(size: 28, weight: .semibold, design: .monospaced))
                .kerning(2)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(Color.secondary.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .textSelection(.enabled)
                .accessibilityLabel(
                    "Verification code \(request.verificationCode.map(String.init).joined(separator: " "))"
                )

            Text("Accept only if the phone shows these same six digits.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            DisclosureGroup("Certificate fingerprint") {
                Text(formatFingerprint(request.fingerprint))
                    .font(.system(.caption2, design: .monospaced))
                    // A SHA-256 fingerprint formats to 95 characters and needs three lines
                    // here. Truncating it would hide exactly the span where a mismatch shows
                    // up, and lineLimit alone is a maximum — without fixedSize the text stays
                    // on one line.
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
            }
            .font(.caption2)

            HStack(spacing: 8) {
                // No keyboard shortcuts: inline in the panel, Escape is the dismiss key and
                // Return the default action, so either would pair or refuse a peer by accident.
                Button("Decline") { onReject() }
                    .accessibilityLabel("Decline pair request from \(request.deviceName)")

                Button("Accept") { onAccept() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityLabel("Accept pair request from \(request.deviceName)")
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// "418207" reads as six digits; "418 207" reads as two groups a person can hold in
    /// their head while looking at the other screen.
    private var spacedCode: String {
        let code = request.verificationCode
        guard code.count == 6 else { return code }
        let mid = code.index(code.startIndex, offsetBy: 3)
        return "\(code[code.startIndex..<mid]) \(code[mid...])"
    }

    private func formatFingerprint(_ hex: String) -> String {
        stride(from: 0, to: hex.count, by: 2).map { i in
            let start = hex.index(hex.startIndex, offsetBy: i)
            let end = hex.index(start, offsetBy: min(2, hex.count - i))
            return String(hex[start..<end]).uppercased()
        }.joined(separator: ":")
    }
}
