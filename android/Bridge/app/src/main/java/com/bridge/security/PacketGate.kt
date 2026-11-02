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
     * The only packets a stranger may send, because they are the packets that establish
     * identity and trust. Everything else, `bridge.ping` included, waits: answering a
     * ping confirms a live Bridge endpoint to a peer that has not identified itself.
     */
    private val ALWAYS_ALLOWED = setOf("bridge.identity", "bridge.pair")

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
