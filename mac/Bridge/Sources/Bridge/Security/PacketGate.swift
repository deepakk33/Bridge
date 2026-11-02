import Foundation

/// The single "may this peer send this packet type?" decision (BR-4).
///
/// Default-deny is the shape of this function, not a branch inside it. The shipped bug
/// was structural: the trust check sat behind `if peerDeviceId != nil, peerFingerprint
/// != nil`, so the most dangerous case — nothing known about the peer at all — was the
/// one case that skipped it.
enum PacketGate {

    /// The only packets a stranger may send, because they are the packets that establish
    /// identity and trust. Everything else, `bridge.ping` included, waits: answering a
    /// ping confirms a live Bridge endpoint to a peer that has not identified itself.
    ///
    /// Gating ping does not strand the link — both sides feed their watchdog when a
    /// packet *arrives*, before this gate runs, so unanswered pings still count as life.
    static let alwaysAllowed: Set<String> = ["bridge.identity", "bridge.pair"]

    static func allows(
        packetType: String,
        peerDeviceId: String?,
        peerFingerprint: String?,
        isTrusted: (String, String) -> Bool
    ) -> Bool {
        if alwaysAllowed.contains(packetType) { return true }
        guard let peerDeviceId, let peerFingerprint else { return false }
        return isTrusted(peerDeviceId, peerFingerprint)
    }
}
