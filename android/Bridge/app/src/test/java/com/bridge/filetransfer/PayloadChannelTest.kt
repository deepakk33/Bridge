package com.bridge.filetransfer

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.net.ssl.SSLContext

class PayloadChannelTest {

    /** Server is peer-a pinned to peer-b; client is peer-b pinned to peer-a. */
    private val serverTls: SSLContext get() = TestTls.context(self = "peer-a", pinnedPeer = "peer-b")
    private val clientTls: SSLContext get() = TestTls.context(self = "peer-b", pinnedPeer = "peer-a")

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    @Test
    fun `loopback round trip checksum matches`() = runBlocking {
        val payload = ByteArray(1024) { (it and 0xFF).toByte() }
        val server = PayloadServer(payload, serverTls)
        val port = server.bind()

        val serving = async { server.serve() }
        val client = PayloadClient("127.0.0.1", port, payload.size.toLong(), clientTls)
        val received = client.fetch()
        val served = serving.await()

        assertEquals(payload.size.toLong(), served)
        assertArrayEquals(payload, received)
        assertArrayEquals(sha256(payload), sha256(received))
    }

    @Test
    fun `client reads exactly expected bytes ignoring excess`() = runBlocking {
        val payload = ByteArray(2048) { 0x5A }
        val server = PayloadServer(payload, serverTls)
        val port = server.bind()

        val serving = async { runCatching { server.serve() } }
        val client = PayloadClient("127.0.0.1", port, 1000L, clientTls)
        val received = client.fetch()
        serving.await()

        assertEquals(1000, received.size)
    }

    @Test
    fun `ephemeral port is assigned`() {
        val server = PayloadServer(byteArrayOf(1, 2, 3), serverTls)
        val port = server.bind()
        assertTrue(port > 0)
        server.close()
    }

    // --- BR-4: mutual TLS with pinned fingerprints ---

    /** peer-c is a stranger: the server pins peer-b, so peer-c must get nothing. */
    @Test
    fun `wrong fingerprint is refused by the server`() = runBlocking {
        val payload = ByteArray(1024) { 0x11 }
        val server = PayloadServer(payload, serverTls)
        val port = server.bind()

        val serving = async { runCatching { server.serve() } }
        val impostor = PayloadClient(
            "127.0.0.1", port, payload.size.toLong(),
            TestTls.context(self = "peer-c", pinnedPeer = "peer-a")
        )
        val result = runCatching { impostor.fetch() }
        serving.await()

        assertTrue("impostor must not receive the payload", result.isFailure)
    }

    /** The client pins too: a server presenting the wrong certificate gets no transfer. */
    @Test
    fun `wrong fingerprint is refused by the client`() = runBlocking {
        val payload = ByteArray(1024) { 0x22 }
        // Server really is peer-c, but the client is pinned to peer-a.
        val server = PayloadServer(payload, TestTls.context(self = "peer-c", pinnedPeer = "peer-b"))
        val port = server.bind()

        val serving = async { runCatching { server.serve() } }
        val client = PayloadClient("127.0.0.1", port, payload.size.toLong(), clientTls)
        val result = runCatching { client.fetch() }
        serving.await()

        assertTrue("client must refuse an unpinned server", result.isFailure)
    }

    /** An eavesdropper on the wire sees TLS records, not the file. */
    @Test
    fun `payload bytes do not appear in the clear on the wire`() = runBlocking {
        val marker = "BRIDGE-SECRET-PAYLOAD-MARKER".toByteArray()
        val payload = ByteArray(4096) { 0x00 }
        marker.copyInto(payload, 128)

        val server = PayloadServer(payload, serverTls)
        val serverPort = server.bind()

        // A relay in the middle, recording everything the client sends to the server.
        val relay = ServerSocket(0)
        val seen = java.io.ByteArrayOutputStream()
        val relaying = async(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                relay.accept().use { downstream ->
                    Socket("127.0.0.1", serverPort).use { upstream ->
                        val toServer = async(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = downstream.getInputStream().read(buf)
                                    if (n < 0) break
                                    synchronized(seen) { seen.write(buf, 0, n) }
                                    upstream.getOutputStream().write(buf, 0, n)
                                    upstream.getOutputStream().flush()
                                }
                            }
                        }
                        runCatching {
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = upstream.getInputStream().read(buf)
                                if (n < 0) break
                                synchronized(seen) { seen.write(buf, 0, n) }
                                downstream.getOutputStream().write(buf, 0, n)
                                downstream.getOutputStream().flush()
                            }
                        }
                        toServer.await()
                    }
                }
            }
        }

        val serving = async { runCatching { server.serve() } }
        val client = PayloadClient("127.0.0.1", relay.localPort, payload.size.toLong(), clientTls)
        val received = runCatching { client.fetch() }
        serving.await()
        relaying.await()
        relay.close()

        assertTrue("the transfer itself should succeed through the relay", received.isSuccess)
        assertArrayEquals(payload, received.getOrThrow())

        val wire = synchronized(seen) { seen.toByteArray() }
        assertTrue("nothing was captured", wire.isNotEmpty())
        assertEquals("the marker must not cross the wire in the clear", -1, indexOf(wire, marker))
        assertNotEquals("the wire must not be the payload verbatim", 0, indexOf(wire, payload))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
