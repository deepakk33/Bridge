import Foundation
import os

/// Handles incoming bridge.ping packets.
struct PingHandler: Sendable {
    private static let logger = Logger(
        subsystem: "com.bridge.mac",
        category: "PingHandler"
    )

    static let packetType = "bridge.ping"

    /// Process an incoming ping packet. Returns true if handled.
    func handle(_ packet: BridgePacket) -> Bool {
        guard packet.type == Self.packetType else {
            return false
        }
        Self.logger.info("Received ping (id: \(packet.id))")
        return true
    }
}
