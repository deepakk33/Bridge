package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class IdentityPacketTest {

    private val testVectorsDir: File by lazy {
        val moduleDir = File(System.getProperty("user.dir"))
        moduleDir.resolve("../../../protocol/test-vectors")
    }

    @Test
    fun `deserialize identity packet from test vector`() {
        val content = testVectorsDir.resolve("identity-packet.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val validCases = vector["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        val macCase = validCases.first {
            it.jsonObject["name"]?.jsonPrimitive?.content == "mac_identity"
        }.jsonObject

        val serialized = macCase["serialized"]?.jsonPrimitive?.content
            ?: throw AssertionError("Missing 'serialized'")

        val packet = BridgePacket.deserialize(serialized)

        assertEquals("bridge.identity", packet.type)
        assertEquals("550e8400-e29b-41d4-a716-446655440000",
            packet.body["deviceId"]?.jsonPrimitive?.content)
        assertEquals("MacBook Air",
            packet.body["deviceName"]?.jsonPrimitive?.content)
        assertEquals("desktop",
            packet.body["deviceType"]?.jsonPrimitive?.content)
        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
            packet.body["certificateFingerprint"]?.jsonPrimitive?.content)
    }

    @Test
    fun `identity with unknown fields parses with lenient deserializer`() {
        val content = testVectorsDir.resolve("identity-packet.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val validCases = vector["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        val futureCase = validCases.first {
            it.jsonObject["name"]?.jsonPrimitive?.content == "identity_with_unknown_fields"
        }.jsonObject

        val packetJson = futureCase["packet"]?.jsonObject
            ?: throw AssertionError("Missing 'packet'")
        val serialized = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), packetJson)

        // Use lenient deserializer since body contains unknown fields
        val packet = BridgePacket.deserializeLenient(serialized)
        assertEquals("bridge.identity", packet.type)
    }

    @Test
    fun `identity factory creates valid packet`() {
        val packet = BridgePacket.identity(
            deviceId = "test-id",
            deviceName = "Test Phone",
            deviceType = "phone",
            protocolVersion = 1,
            certificateFingerprint = "abc123",
            incomingCapabilities = listOf("bridge.ping", "bridge.pair"),
            outgoingCapabilities = listOf("bridge.ping", "bridge.pair")
        )

        assertEquals("bridge.identity", packet.type)
        assertEquals("test-id", packet.body["deviceId"]?.jsonPrimitive?.content)
        assertEquals("abc123", packet.body["certificateFingerprint"]?.jsonPrimitive?.content)
        assertNotNull(packet.body["incomingCapabilities"]?.jsonArray)
        assertEquals(2, packet.body["incomingCapabilities"]?.jsonArray?.size)
    }

    @Test
    fun `identity packet round-trip`() {
        val packet = BridgePacket.identity(
            deviceId = "round-trip-id",
            deviceName = "Round Trip",
            deviceType = "desktop",
            protocolVersion = 1,
            certificateFingerprint = "deadbeef",
            incomingCapabilities = listOf("bridge.ping"),
            outgoingCapabilities = listOf("bridge.ping")
        )

        val serialized = String(packet.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals(packet.type, deserialized.type)
        assertEquals(
            packet.body["deviceId"]?.jsonPrimitive?.content,
            deserialized.body["deviceId"]?.jsonPrimitive?.content
        )
    }
}
