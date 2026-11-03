package com.bridge.filetransfer

/**
 * Bounds on what a peer may assert about an incoming file. Kept in step with the Mac's
 * `FileReceiveController.safeName` / `isAcceptableSize`; the shared vectors in
 * `protocol/test-vectors/incoming-file-guard.json` check both sides against the same inputs.
 */
object IncomingFileGuard {

    /** Largest payload accepted, refused before a connection is made or a file created. */
    const val MAX_PAYLOAD_BYTES = 2L * 1024 * 1024 * 1024  // 2 GiB

    private const val FALLBACK = "BridgeFile"

    fun isAcceptableSize(payloadSize: Long): Boolean =
        payloadSize > 0 && payloadSize <= MAX_PAYLOAD_BYTES

    /**
     * Reduce a peer-supplied filename to something that can only land inside the download
     * directory. The name is untrusted input: it must not be able to steer the path.
     */
    fun safeName(raw: String): String {
        val flattened = raw.replace('\\', '/').replace(':', '_')
        val last = flattened.split('/').lastOrNull { it.isNotEmpty() } ?: ""
        val trimmed = last.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return FALLBACK
        // Leave room under the 255-byte limit for any de-duplication suffix.
        if (trimmed.toByteArray(Charsets.UTF_8).size <= 200) return trimmed
        val ext = trimmed.substringAfterLast('.', "")
        val stem = trimmed.substringBeforeLast('.').take(150)
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }
}
