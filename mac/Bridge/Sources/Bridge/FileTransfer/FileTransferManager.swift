import Foundation
import os

/// Orchestrates an outbound file transfer: selects the tier, opens the payload
/// channel, and emits the `bridge.share.request` control packet. The actual
/// control-channel send is injected so this stays decoupled from BLEManager.
///
/// Tier 3 (Wi-Fi LAN) is driven here end-to-end via PayloadServer + mDNS. Tier 2
/// (RFCOMM) serving is delegated to RfcommTransfer on real hardware.
@MainActor
final class FileTransferManager: ObservableObject {
    private static let logger = Logger(subsystem: "com.bridge.mac", category: "FileTransferManager")

    enum TransferState: Equatable {
        case idle
        case preparing(filename: String)
        case inProgress(filename: String, tier: TransferTier)
        case slowFallbackWarning(filename: String)
        case completed(filename: String)
        case failed(filename: String, reason: String)
    }

    @Published private(set) var state: TransferState = .idle

    private let sendPacket: (BridgePacket) -> Void
    private var activeServer: PayloadServer?

    init(sendPacket: @escaping (BridgePacket) -> Void) {
        self.sendPacket = sendPacket
    }

    /// Send a file to the phone. `sharedNetworkAvailable` gates Tier 3.
    func sendFile(at url: URL, sharedNetworkAvailable: Bool) {
        let filename = url.lastPathComponent
        state = .preparing(filename: filename)

        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            state = .failed(filename: filename, reason: "Could not read file")
            return
        }

        let size = Int64(data.count)
        let decision = TierRouter.decide(payloadSize: size, sharedNetworkAvailable: sharedNetworkAvailable)
        if decision.isSlowFallback {
            state = .slowFallbackWarning(filename: filename)
        }

        switch decision.tier {
        case .wifiLan:
            startWifiTransfer(filename: filename, data: data, size: size)
        case .bluetooth:
            // Tier 2: no TCP port; receiver connects over the RFCOMM UUID.
            // Serving the bytes over RFCOMM happens on real hardware via RfcommTransfer.
            sendPacket(BridgePacket.shareRequest(filename: filename, fileSize: size, payloadSize: size, port: nil))
            state = .inProgress(filename: filename, tier: .bluetooth)
        }
    }

    private func startWifiTransfer(filename: String, data: Data, size: Int64) {
        do {
            let server = try PayloadServer(payload: data, advertiseBonjour: true)
            activeServer = server
            server.start(onReady: { [weak self] port in
                Task { @MainActor in
                    guard let self else { return }
                    let host = Self.localIPv4()
                    self.sendPacket(BridgePacket.shareRequest(filename: filename, fileSize: size, payloadSize: size, port: port, host: host))
                    self.state = .inProgress(filename: filename, tier: .wifiLan)
                }
            }, completion: { [weak self] result in
                Task { @MainActor in
                    guard let self else { return }
                    self.activeServer = nil
                    switch result {
                    case .success:
                        self.state = .completed(filename: filename)
                    case .failure(let error):
                        self.state = .failed(filename: filename, reason: "\(error)")
                    }
                }
            })
        } catch {
            state = .failed(filename: filename, reason: "Could not open transfer channel")
        }
    }

    /// First non-loopback site-local IPv4 address (the Wi-Fi/LAN address).
    static func localIPv4() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0 else { continue }
            let family = ptr.pointee.ifa_addr.pointee.sa_family
            guard family == UInt8(AF_INET) else { continue }
            let name = String(cString: ptr.pointee.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(ptr.pointee.ifa_addr, socklen_t(ptr.pointee.ifa_addr.pointee.sa_len),
                        &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") { continue }
            address = ip
            break
        }
        return address
    }
}
