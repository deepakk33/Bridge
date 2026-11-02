package com.bridge.security

/**
 * The single "may this peer send this packet type?" decision (BR-4).
 *
 * Default-deny is the shape of this function, not a branch inside it. The shipped bug
 * was structural: the trust check sat inside `if (peerDeviceId != null && ...)`, so the
 * most dangerous case — nothing known about the peer at all — was the one case that
 * skipped it.
 */
object PacketGate {

    /**
     * The only packets a stranger may send: the two that establish identity and trust,
     * plus the heartbeat. A ping carries no body, and both sides already send one to an
     * unverified peer, so refusing to receive one hid nothing — it only stranded the
     * link, because the 3-second probe needs a packet back and a pair confirmation can
     * sit unanswered for minutes with nothing else to send (BR-19).
     */
    private val ALWAYS_ALLOWED = setOf("bridge.identity", "bridge.pair", "bridge.ping")

    fun allows(
        packetType: String,
        peerDeviceId: String?,
        peerFingerprint: String?,
        trustStore: () -> TrustStoreAccess
    ): Boolean {
        if (packetType in ALWAYS_ALLOWED) return true
        if (peerDeviceId == null || peerFingerprint == null) return false
        return try {
            trustStore().isTrusted(peerDeviceId, peerFingerprint)
        } catch (e: Exception) {
            // An uninitialised or unreadable trust store means "this peer is not
            // trusted", never "skip the check" — which is what the swallowed
            // IllegalStateException used to mean here.
            false
        }
    }
}
