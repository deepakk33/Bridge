package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BridgePacketTest {

    private val testVectorsDir: File by lazy {
        // Gradle sets user.dir to the module directory (app/).
        // Test vectors are at ../../protocol/test-vectors/ relative to the module.
        val moduleDir = File(System.getProperty("user.dir"))
        val vectorsDir = moduleDir.resolve("../../../protocol/test-vectors")
        assertTrue(
            "Test vectors directory not found at ${vectorsDir.absolutePath}",
            vectorsDir.exists()
        )
        vectorsDir
    }

    @Test
    fun `deserialize valid ping packet from test vector`() {
        val content = testVectorsDir.resolve("ping.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val packetJson = vector["packet"]?.jsonObject
            ?: throw AssertionError("Missing 'packet' in test vector")

        val serialized = vector["serialized"]?.jsonPrimitive?.content
            ?: throw AssertionError("Missing 'serialized' in test vector")

        val packet = BridgePacket.deserialize(serialized)

        assertEquals(
            packetJson["id"]?.jsonPrimitive?.long,
            packet.id
        )
        assertEquals(
            packetJson["type"]?.jsonPrimitive?.content,
            packet.type
        )
        assertEquals("bridge.ping", packet.type)
    }

    @Test
    fun `serialize produces expected output from test vector`() {
        val content = testVectorsDir.resolve("ping.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject

        val expectedSerialized = vector["serialized"]?.jsonPrimitive?.content
            ?: throw AssertionError("Missing 'serialized' in test vector")

        val packet = BridgePacket(
            id = 1728000000000L,
            type = "bridge.ping",
            body = buildJsonObject {}
        )

        val serialized = String(packet.serialize(), Charsets.UTF_8)
        assertEquals(expectedSerialized, serialized)
    }

    @Test
    fun `serialization round-trip preserves data`() {
        val original = BridgePacket(
            id = 1728000000000L,
            type = "bridge.ping",
            body = buildJsonObject {}
        )

        val serialized = String(original.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals(original.id, deserialized.id)
        assertEquals(original.type, deserialized.type)
        assertEquals(original.body, deserialized.body)
    }

    @Test
    fun `malformed packets from test vectors are rejected`() {
        val content = testVectorsDir.resolve("malformed.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val cases = vector["cases"]
            ?: throw AssertionError("Missing 'cases' in malformed test vectors")

        for (case in (cases as kotlinx.serialization.json.JsonArray)) {
            val caseObj = case.jsonObject
            val name = caseObj["name"]?.jsonPrimitive?.content ?: "unknown"
            val input = caseObj["input"]?.jsonPrimitive?.content ?: continue

            try {
                BridgePacket.deserialize(input)
                fail("Expected exception for malformed case '$name' but deserialization succeeded")
            } catch (_: Exception) {
                // Expected: any exception (IllegalArgumentException, SerializationException, etc.)
            }
        }
    }

    @Test
    fun `ping factory creates valid packet`() {
        val packet = BridgePacket.ping()

        assertEquals("bridge.ping", packet.type)
        assertTrue("id should be positive", packet.id > 0)
        assertNotNull(packet.body)
        assertEquals(buildJsonObject {}, packet.body)
    }

    @Test
    fun `serialize ends with newline`() {
        val packet = BridgePacket.ping()
        val bytes = packet.serialize()
        val str = String(bytes, Charsets.UTF_8)
        assertTrue("Serialized packet must end with newline", str.endsWith("\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative id is rejected`() {
        BridgePacket(
            id = -1,
            type = "bridge.ping",
            body = buildJsonObject {}
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank type is rejected`() {
        BridgePacket(
            id = 1728000000000L,
            type = "",
            body = buildJsonObject {}
        )
    }
}
