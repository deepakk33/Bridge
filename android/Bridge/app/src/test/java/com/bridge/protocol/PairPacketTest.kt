package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PairPacketTest {

    private val testVectorsDir: File by lazy {
        val moduleDir = File(System.getProperty("user.dir"))
        moduleDir.resolve("../../../protocol/test-vectors")
    }

    @Test
    fun `deserialize pair request from test vector`() {
        val content = testVectorsDir.resolve("pair-request.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val validCases = vector["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        val requestCase = validCases.first {
            it.jsonObject["name"]?.jsonPrimitive?.content == "pair_request"
        }.jsonObject

        val serialized = requestCase["serialized"]?.jsonPrimitive?.content
            ?: throw AssertionError("Missing 'serialized'")

        val packet = BridgePacket.deserialize(serialized)

        assertEquals("bridge.pair", packet.type)
        assertTrue(packet.body["pair"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `deserialize pair reject from test vector`() {
        val content = testVectorsDir.resolve("pair-request.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val validCases = vector["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        val rejectCase = validCases.first {
            it.jsonObject["name"]?.jsonPrimitive?.content == "pair_reject"
        }.jsonObject

        val serialized = rejectCase["serialized"]?.jsonPrimitive?.content
            ?: throw AssertionError("Missing 'serialized'")

        val packet = BridgePacket.deserialize(serialized)

        assertEquals("bridge.pair", packet.type)
        assertFalse(packet.body["pair"]?.jsonPrimitive?.boolean ?: true)
    }

    @Test
    fun `pair request factory`() {
        val packet = BridgePacket.pairRequest()
        assertEquals("bridge.pair", packet.type)
        assertTrue(packet.body["pair"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `pair accept factory`() {
        val packet = BridgePacket.pairAccept()
        assertEquals("bridge.pair", packet.type)
        assertTrue(packet.body["pair"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `pair reject factory`() {
        val packet = BridgePacket.pairReject()
        assertEquals("bridge.pair", packet.type)
        assertFalse(packet.body["pair"]?.jsonPrimitive?.boolean ?: true)
    }

    @Test
    fun `pair packet round-trip`() {
        val original = BridgePacket.pairRequest()
        val serialized = String(original.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals(original.type, deserialized.type)
        assertEquals(
            original.body["pair"]?.jsonPrimitive?.boolean,
            deserialized.body["pair"]?.jsonPrimitive?.boolean
        )
    }

    @Test
    fun `all pair vectors deserialize`() {
        val content = testVectorsDir.resolve("pair-request.json").readText()
        val vector = Json.parseToJsonElement(content).jsonObject
        val validCases = vector["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        for (case in validCases) {
            val name = case.jsonObject["name"]?.jsonPrimitive?.content ?: "unknown"
            val serialized = case.jsonObject["serialized"]?.jsonPrimitive?.content ?: continue
            val packet = BridgePacket.deserialize(serialized)
            assertEquals("bridge.pair", packet.type)
        }
    }
}
