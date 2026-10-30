package com.bridge.filetransfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Out-of-band TCP payload client. Connects to the sender's advertised host+port
 * and reads exactly [expectedBytes]. Discards partial transfers so a corrupt file
 * is never saved; reads at most [expectedBytes], ignoring any excess.
 *
 * The connection is mutual TLS against the sender's pinned fingerprint, and the
 * handshake is completed before any byte is read, so a wrong certificate fails the
 * transfer rather than producing a file from an unknown source.
 */
class PayloadClient(
    private val host: String,
    private val port: Int,
    private val expectedBytes: Long,
    private val sslContext: SSLContext,
    private val timeoutMs: Int = 30_000
) {
    companion object {
        private const val CHUNK = 16 * 1024
    }

    /**
     * Connects, then layers TLS over the connected socket so the connect timeout still
     * applies, and forces the handshake so a pin mismatch surfaces here rather than as a
     * truncated read later.
     */
    private fun connect(): SSLSocket {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            plain.soTimeout = timeoutMs
            val ssl = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            return ssl
        } catch (e: Throwable) {
            // A refused pin throws here, and a retry per mismatch would leak one fd each.
            try { plain.close() } catch (_: IOException) {}
            throw e
        }
    }

    /** Connect, read exactly [expectedBytes], close. Throws on incomplete transfer. */
    suspend fun fetch(): ByteArray = withContext(Dispatchers.IO) {
        connect().use { socket ->
            val input = socket.getInputStream()
            val initial = expectedBytes.coerceAtMost((1 shl 20).toLong()).toInt().coerceAtLeast(0)
            val buffer = ByteArrayOutputStream(initial)
            val chunk = ByteArray(CHUNK)
            var total = 0L
            while (total < expectedBytes) {
                val toRead = minOf(chunk.size.toLong(), expectedBytes - total).toInt()
                val n = input.read(chunk, 0, toRead)
                if (n < 0) {
                    throw IOException("incomplete transfer: $total of $expectedBytes bytes")
                }
                buffer.write(chunk, 0, n)
                total += n
            }
            buffer.toByteArray()
        }
    }

    /**
     * Connect and stream exactly [expectedBytes] into [out], reporting progress.
     * Streams in chunks so a large file never sits fully in memory. Returns bytes
     * written; throws on incomplete transfer (caller discards the partial file).
     */
    suspend fun fetchTo(
        out: OutputStream,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): Long = withContext(Dispatchers.IO) {
        connect().use { socket ->
            val input = socket.getInputStream()
            val chunk = ByteArray(CHUNK)
            var total = 0L
            while (total < expectedBytes) {
                val toRead = minOf(chunk.size.toLong(), expectedBytes - total).toInt()
                val n = input.read(chunk, 0, toRead)
                if (n < 0) throw IOException("incomplete transfer: $total of $expectedBytes bytes")
                out.write(chunk, 0, n)
                total += n
                onProgress(total, expectedBytes)
            }
            out.flush()
            total
        }
    }
}
