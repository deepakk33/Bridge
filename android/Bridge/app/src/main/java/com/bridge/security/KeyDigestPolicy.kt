package com.bridge.security

/**
 * Whether a stored key can still be used for TLS.
 *
 * Conscrypt hashes the handshake transcript itself and asks the key to sign the raw digest
 * (`NONEwithECDSA`). A key whose authorised digests omit `NONE` is refused by AndroidKeyStore
 * mid-handshake, and those authorisations are fixed at generation — so the key has to be replaced
 * rather than amended.
 */
object KeyDigestPolicy {

    private const val RAW_DIGEST = "NONE"

    /** An empty list means the key carries no digest restriction, so there is nothing to repair. */
    fun needsRegeneration(authorisedDigests: List<String>): Boolean =
        authorisedDigests.isNotEmpty() &&
            authorisedDigests.none { it.equals(RAW_DIGEST, ignoreCase = true) }
}
