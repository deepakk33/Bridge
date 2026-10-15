package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
    fun `ping round-trip`() {
        val original = BridgePacket.ping()
        val serialized = String(original.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals("bridge.ping", deserialized.type)
        assertTrue(deserialized.body.isEmpty())
    }
}
