import SwiftUI

/// The phones an unpaired Mac can see, by name and signal strength (BR-5).
///
/// Every Bridge install advertises the same service UUID, so a scan in a shared space
/// returns several phones. The Mac waits for a choice rather than connecting to the
/// first advertiser it happens to hear.
struct DevicePickerView: View {
    let devices: [DiscoveredDevice]
    let onPick: (UUID) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Choose your phone", systemImage: "iphone.gen3")
                .font(.subheadline.bold())
                .foregroundStyle(.blue)

            Text("Bridge is not paired yet. Pick the phone to pair with.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if devices.isEmpty {
                Text("Looking for phones running Bridge…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 4)
            } else {
                ForEach(devices) { device in
                    Button {
                        onPick(device.id)
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: signalIcon(for: device.rssi))
                                .foregroundStyle(.secondary)
                            Text(device.name)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            Spacer(minLength: 8)
                            Text("\(device.rssi) dBm")
                                .font(.caption2.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Pair with \(device.name), signal \(device.rssi) decibel milliwatts")
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.blue.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// RSSI is negative; closer to zero is stronger. The thresholds only have to be
    /// good enough to tell the phone on the desk from one across the room.
    private func signalIcon(for rssi: Int) -> String {
        switch rssi {
        case (-55)...: return "wifi"
        case (-75)..<(-55): return "wifi.exclamationmark"
        default: return "wifi.slash"
        }
    }
}
