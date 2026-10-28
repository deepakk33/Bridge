package com.bridge.clipboard

/**
 * Bounds the clipboard text that leaves the phone. The PRD sends payloads over 10 KB out of
 * band; clipboard has no out-of-band path, so it truncates instead and the sender reports it.
 */
object ClipboardCap {

    const val MAX_BYTES = 10_240

    data class Capped(val text: String, val originalBytes: Int, val truncated: Boolean)

    fun apply(text: String): Capped {
        val originalBytes = text.toByteArray(Charsets.UTF_8).size
        if (originalBytes <= MAX_BYTES) return Capped(text, originalBytes, false)

        // Measure in bytes, cut on a code point: a cut mid-sequence decodes as U+FFFD.
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val width = utf8Length(codePoint)
            if (bytes + width > MAX_BYTES) break
            bytes += width
            end += Character.charCount(codePoint)
        }
        return Capped(text.substring(0, end), originalBytes, true)
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
