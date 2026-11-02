import Foundation

/// The single "may this peer send this packet type?" decision (BR-4).
///
/// Default-deny is the shape of this function, not a branch inside it. The shipped bug
/// was structural: the trust check sat behind `if peerDeviceId != nil, peerFingerprint
/// != nil`, so the most dangerous case — nothing known about the peer at all — was the
/// one case that skipped it.
enum PacketGate {

    /// The only packets a stranger may send: the two that establish identity and trust,
    /// plus the heartbeat. A ping carries no body, and refusing it bought nothing — the
    /// Mac already *sends* pings to an unverified peer (`preVerificationPacketTypes`),
    /// so a stranger holding a channel open learns a Bridge endpoint is live either way.
    ///
    /// Refusing it did cost something: the phone's 3-second link probe needs a packet
    /// back, and a pair confirmation can sit unanswered for minutes with nothing else
    /// to send. The 90-second watchdog feeds on arrival and never noticed; the probe did.
    static let alwaysAllowed: Set<String> = ["bridge.identity", "bridge.pair", "bridge.ping"]

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
