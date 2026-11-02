package com.bridge.security

import java.security.MessageDigest

/**
 * The six digits both devices display before either side pins anything.
 *
 * Derived from the two certificate fingerprints and never transmitted: a peer in the
 * middle necessarily presents a different certificate to at least one side, so the two
 * screens disagree and the user declines. Sorting the inputs makes the result
 * order-independent, so neither side needs to know which one initiated.
 *
 * The Mac implementation must agree byte for byte — both are checked against
 * `protocol/test-vectors/pairing-code.json`.
 */
object PairingCode {

    /** @return exactly six decimal digits, zero-padded on the left. */
    fun derive(a: String, b: String): String {
        val sorted = listOf(a.lowercase(), b.lowercase()).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sorted.joinToString("").toByteArray(Charsets.UTF_8))
        var leading = 0L
        for (i in 0 until 4) {
            leading = (leading shl 8) or (digest[i].toLong() and 0xFF)
        }
        return (leading % 1_000_000).toString().padStart(6, '0')
    }
}
