package com.bridge.security

/**
 * The slice of the trust store that pairing and the packet gate need.
 *
 * Exists so trust decisions — the ones that must fail closed — can be tested without an
 * Android Context. [TrustStore] is the production implementation.
 */
interface TrustStoreAccess {
    fun isTrusted(deviceId: String, fingerprint: String): Boolean
    fun pinnedFingerprint(deviceId: String): String?
    fun pin(deviceId: String, fingerprint: String)
    fun unpin(deviceId: String)
    val isEmpty: Boolean
}
