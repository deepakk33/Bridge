package com.bridge.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BR-8: conscrypt hashes the TLS transcript itself and asks the key to sign the raw digest
 * (NONEwithECDSA). A key not authorised for DIGEST_NONE cannot do that, and AndroidKeyStore
 * refuses the operation mid-handshake — which is why no Tier 3 transfer ever completed.
 * A key's authorisations are fixed at generation, so such a key has to be replaced.
 */
class KeyDigestPolicyTest {

    @Test
    fun `a key without DIGEST_NONE cannot sign for TLS and must be replaced`() {
        assertTrue(KeyDigestPolicy.needsRegeneration(listOf("SHA-256")))
    }

    @Test
    fun `a key authorised for the raw digest is usable`() {
        assertFalse(KeyDigestPolicy.needsRegeneration(listOf("NONE", "SHA-256")))
    }

    @Test
    fun `digest names are matched regardless of case`() {
        assertFalse(KeyDigestPolicy.needsRegeneration(listOf("none", "sha-256")))
    }

    @Test
    fun `a key reporting no digest restrictions is usable`() {
        // An unrestricted key accepts any digest; nothing to repair.
        assertFalse(KeyDigestPolicy.needsRegeneration(emptyList()))
    }
}
