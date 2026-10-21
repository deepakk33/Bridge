package com.bridge.filetransfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Out-of-band TCP payload client. Connects to the sender's advertised host+port
 * and reads exactly [expectedBytes]. Discards partial transfers so a corrupt file
 * is never saved; reads at most [expectedBytes], ignoring any excess.
 */
class PayloadClient(
    private val host: String,
    private val port: Int,
    private val expectedBytes: Long,
    private val timeoutMs: Int = 30_000
) {
    companion object {
        private const val CHUNK = 16 * 1024
    }

    /** Connect, read exactly [expectedBytes], close. Throws on incomplete transfer. */
    suspend fun fetch(): ByteArray = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
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
}
