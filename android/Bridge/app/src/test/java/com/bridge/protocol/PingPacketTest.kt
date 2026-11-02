package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PingPacketTest {

    private val testVectorsDir: File by lazy {
        val moduleDir = File(System.getProperty("user.dir"))
        moduleDir.resolve("../../../protocol/test-vectors")
    }

    @Test
    fun `deserialize ping packets from test vector`() {
        val content = testVectorsDir.resolve("ping-packet.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val validCases = vector["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        for (case in validCases) {
            val name = case.jsonObject["name"]?.jsonPrimitive?.content ?: "unknown"
            val serialized = case.jsonObject["serialized"]?.jsonPrimitive?.content ?: continue
            val packet = BridgePacket.deserialize(serialized)
            assertEquals("$name should be bridge.ping", "bridge.ping", packet.type)
            assertTrue("$name body should be empty", packet.body.isEmpty())
        }
    }

    @Test
    fun `reply vectors carry replyTo`() {
        val content = testVectorsDir.resolve("ping-packet.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val replyCases = vector["reply"]?.jsonArray ?: throw AssertionError("Missing 'reply'")
        assertTrue("reply vectors should not be empty", replyCases.isNotEmpty())

        for (case in replyCases) {
            val name = case.jsonObject["name"]?.jsonPrimitive?.content ?: "unknown"
            val expected = case.jsonObject["replyTo"]?.jsonPrimitive?.long
            val serialized = case.jsonObject["serialized"]?.jsonPrimitive?.content ?: continue
            val packet = BridgePacket.deserialize(serialized)
            assertEquals("$name should be bridge.ping", "bridge.ping", packet.type)
            assertEquals(
                "$name should carry replyTo",
                expected,
                packet.body["replyTo"]?.jsonPrimitive?.long
            )
        }
    }

    @Test
    fun `handler answers a plain ping`() {
        val ping = BridgePacket.ping()
        val reply = PingHandler.handle(ping) ?: throw AssertionError("a plain ping must be answered")

        assertEquals("bridge.ping", reply.type)
        assertEquals(ping.id, reply.body["replyTo"]?.jsonPrimitive?.long)
    }

    /**
     * The loop guard. Now that the Mac answers too, a handler that answers a reply
     * ping-pongs at line rate for the life of the connection.
     */
    @Test
    fun `handler does not answer a reply`() {
        val reply = PingHandler.handle(BridgePacket.ping())
            ?: throw AssertionError("a plain ping must be answered")
        assertNull("a reply must be terminal", PingHandler.handle(reply))
    }

    @Test
    fun `ping round-trip`() {
        val original = BridgePacket.ping()
        val serialized = String(original.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals("bridge.ping", deserialized.type)
        assertTrue(deserialized.body.isEmpty())
    }
}
