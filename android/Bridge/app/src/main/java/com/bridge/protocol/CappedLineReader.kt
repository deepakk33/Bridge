package com.bridge.protocol

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads newline-delimited lines with a hard ceiling on how long one line may be (BR-4).
 *
 * `BufferedReader.readLine()` has no bound, so one peer-controlled line could grow until
 * the heap was gone. A peer sending an oversized line is broken or hostile; the stream is
 * not resynchronised from an attacker-chosen offset, it is closed, and the link reconnects
 * on its own.
 *
 * Bytes are accumulated and decoded as UTF-8 once per line, not per byte: clipboard text
 * and notification bodies are full Unicode, and decoding byte by byte would mangle every
 * multi-byte character.
 */
class CappedLineReader(
    input: InputStream,
    private val cap: Int = DEFAULT_CAP
) {
    companion object {
        /**
         * Payloads above 10 KB travel out of band, so a control packet has roughly 25x
         * the headroom it can legitimately need, while a hostile peer cannot grow memory
         * meaningfully.
         */
        const val DEFAULT_CAP = 256 * 1024
    }

    class LineTooLongException(bytesSeen: Int, cap: Int) :
        IOException("Line exceeded $cap bytes (saw $bytesSeen) — closing connection")

    private val input = BufferedInputStream(input)
    private val buffer = ByteArrayOutputStream()

    /**
     * @return the next line without its terminator, or null at end of stream.
     * @throws LineTooLongException once [cap] bytes have arrived with no newline.
     */
    fun readLine(): String? {
        buffer.reset()
        var seen = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buffer.size() == 0) null else decode()
            }
            if (b == NEWLINE) return decode()
            // The cap counts every byte read, not just buffered ones: skipping it for
            // '\r' would let a peer stream carriage returns forever, never hitting the
            // bound and never returning a line.
            seen++
            if (seen > cap) throw LineTooLongException(seen, cap)
            // A lone '\r' is not a terminator; the wire format is '\n'-delimited, and
            // dropping it keeps a CRLF-producing peer parseable.
            if (b == CARRIAGE_RETURN) continue
            buffer.write(b)
        }
    }

    private fun decode(): String = buffer.toString(Charsets.UTF_8.name())
}

private const val NEWLINE = '\n'.code
private const val CARRIAGE_RETURN = '\r'.code
