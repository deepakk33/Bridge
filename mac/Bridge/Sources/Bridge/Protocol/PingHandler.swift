import Foundation
import os

/// Handles incoming bridge.ping packets.
struct PingHandler: Sendable {
    private static let logger = Logger(
        subsystem: "com.bridge.mac",
        category: "PingHandler"
    )

    static let packetType = "bridge.ping"

    /// Process an incoming ping packet. Returns the reply to send, or nil when there
    /// is nothing to answer — a packet of another type, or a ping that is itself a
    /// reply. Answering a reply would loop at line rate, since Android answers too.
    func handle(_ packet: BridgePacket) -> BridgePacket? {
        guard packet.type == Self.packetType else {
            return nil
        }
        guard packet.body["replyTo"] == nil else {
            Self.logger.debug("Received ping reply (id: \(packet.id))")
            return nil
        }
        Self.logger.info("Received ping (id: \(packet.id))")
        return .pingReply(to: packet.id)
    }
}
