package com.bridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class CappedLineReaderTest {

    private fun reader(text: String, cap: Int = CappedLineReader.DEFAULT_CAP) =
        CappedLineReader(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), cap)

    @Test
    fun `reads newline delimited lines`() {
        val r = reader("one\ntwo\nthree\n")
        assertEquals("one", r.readLine())
        assertEquals("two", r.readLine())
        assertEquals("three", r.readLine())
        assertNull(r.readLine())
    }

    @Test
    fun `final line without a newline still arrives`() {
        val r = reader("only")
        assertEquals("only", r.readLine())
        assertNull(r.readLine())
    }

    /** Clipboard text and notification bodies are full Unicode. */
    @Test
    fun `multi byte utf8 survives`() {
        val text = "héllo → 世界 🚀"
        val r = reader("$text\n")
        assertEquals(text, r.readLine())
    }

    @Test
    fun `crlf peers are parseable`() {
        val r = reader("one\r\ntwo\r\n")
        assertEquals("one", r.readLine())
        assertEquals("two", r.readLine())
    }

    @Test
    fun `a line at the cap still passes`() {
        val line = "x".repeat(64)
        assertEquals(line, reader("$line\n", cap = 64).readLine())
    }

    @Test
    fun `a line over the cap throws`() {
        val e = runCatching { reader("x".repeat(65) + "\n", cap = 64).readLine() }.exceptionOrNull()
        assertTrue("expected LineTooLongException, got $e", e is CappedLineReader.LineTooLongException)
    }

    /** The point of the cap: memory stays bounded however much the peer sends. */
    @Test
    fun `endless stream with no newline is bounded`() {
        val endless = object : InputStream() {
            var served = 0
            override fun read(): Int { served++; return 'x'.code }
        }
        val r = CappedLineReader(endless, cap = 1024)
        val e = runCatching { r.readLine() }.exceptionOrNull()
        assertTrue(e is CappedLineReader.LineTooLongException)
        // Buffered reads mean a little over the cap may be pulled from the stream, but
        // it is a constant factor of the cap, not unbounded.
        assertTrue("served ${endless.served}", endless.served < 1024 * 16)
    }

    @Test
    fun `empty lines are returned not skipped`() {
        val r = reader("\na\n")
        assertEquals("", r.readLine())
        assertEquals("a", r.readLine())
    }
}
