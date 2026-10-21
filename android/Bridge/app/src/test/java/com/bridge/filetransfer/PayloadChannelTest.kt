package com.bridge.filetransfer

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PayloadChannelTest {

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    @Test
    fun `loopback round trip checksum matches`() = runBlocking {
        val payload = ByteArray(1024) { (it and 0xFF).toByte() }
        val server = PayloadServer(payload)
        val port = server.bind()

        val serving = async { server.serve() }
        val client = PayloadClient("127.0.0.1", port, payload.size.toLong())
        val received = client.fetch()
        val served = serving.await()

        assertEquals(payload.size, served)
        assertArrayEquals(payload, received)
        assertArrayEquals(sha256(payload), sha256(received))
    }

    @Test
    fun `client reads exactly expected bytes ignoring excess`() = runBlocking {
        val payload = ByteArray(2048) { 0x5A }
        val server = PayloadServer(payload)
        val port = server.bind()

        val serving = async { runCatching { server.serve() } }
        val client = PayloadClient("127.0.0.1", port, 1000L)
        val received = client.fetch()
        serving.await()

        assertEquals(1000, received.size)
    }

    @Test
    fun `ephemeral port is assigned`() {
        val server = PayloadServer(byteArrayOf(1, 2, 3))
        val port = server.bind()
        assertTrue(port > 0)
        server.close()
    }
}
