package com.bridge.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCapTest {

    @Test
    fun `text under the cap passes through unchanged`() {
        val text = "the quick brown fox"
        val capped = ClipboardCap.apply(text)
        assertEquals(text, capped.text)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, capped.originalBytes)
        assertFalse(capped.truncated)
    }

    @Test
    fun `empty text is not a truncation`() {
        val capped = ClipboardCap.apply("")
        assertEquals("", capped.text)
        assertEquals(0, capped.originalBytes)
        assertFalse(capped.truncated)
    }

    @Test
    fun `text exactly at the cap is not truncated`() {
        val text = "a".repeat(ClipboardCap.MAX_BYTES)
        val capped = ClipboardCap.apply(text)
        assertEquals(text, capped.text)
        assertEquals(ClipboardCap.MAX_BYTES, capped.originalBytes)
        assertFalse(capped.truncated)
    }

    @Test
    fun `oversized ascii is cut to the cap and reports the original size`() {
        val text = "a".repeat(ClipboardCap.MAX_BYTES + 500)
        val capped = ClipboardCap.apply(text)
        assertTrue(capped.truncated)
        assertEquals(ClipboardCap.MAX_BYTES, capped.text.toByteArray(Charsets.UTF_8).size)
        assertEquals(ClipboardCap.MAX_BYTES + 500, capped.originalBytes)
    }

    @Test
    fun `cut inside a multi-byte character falls back to the previous boundary`() {
        // 'あ' is 3 UTF-8 bytes; 'a' padding puts the cap boundary inside one of them.
        val text = "a".repeat(ClipboardCap.MAX_BYTES - 1) + "あ".repeat(10)
        val capped = ClipboardCap.apply(text)

        assertTrue(capped.truncated)
        assertTrue(capped.text.toByteArray(Charsets.UTF_8).size <= ClipboardCap.MAX_BYTES)
        assertEquals("a".repeat(ClipboardCap.MAX_BYTES - 1), capped.text)
        assertNoReplacementChar(capped.text)
    }

    @Test
    fun `cut inside a surrogate pair does not split the pair`() {
        // U+1F600 is one code point, two Kotlin chars, four UTF-8 bytes.
        val emoji = "😀"
        val text = "a".repeat(ClipboardCap.MAX_BYTES - 2) + emoji.repeat(10)
        val capped = ClipboardCap.apply(text)

        assertTrue(capped.truncated)
        assertTrue(capped.text.toByteArray(Charsets.UTF_8).size <= ClipboardCap.MAX_BYTES)
        assertFalse("a lone high surrogate survived the cut", capped.text.last().isHighSurrogate())
        assertNoReplacementChar(capped.text)
    }

    @Test
    fun `multi-byte text is measured in bytes not characters`() {
        // MAX_BYTES characters of 'あ' is 3x the cap in bytes, so it must be cut even though
        // its character count equals the cap.
        val text = "あ".repeat(ClipboardCap.MAX_BYTES)
        val capped = ClipboardCap.apply(text)

        assertTrue(capped.truncated)
        assertTrue(capped.text.toByteArray(Charsets.UTF_8).size <= ClipboardCap.MAX_BYTES)
        assertEquals(ClipboardCap.MAX_BYTES / 3, capped.text.length)
        assertNoReplacementChar(capped.text)
    }

    /** Re-encode and decode: a split sequence shows up as U+FFFD. */
    private fun assertNoReplacementChar(text: String) {
        val roundTripped = String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(text, roundTripped)
        assertFalse("text contains a replacement character", roundTripped.contains('�'))
    }
}
