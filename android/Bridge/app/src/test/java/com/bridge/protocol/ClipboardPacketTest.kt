package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipboardPacketTest {

    private val testVectorsDir: File by lazy {
        val moduleDir = File(System.getProperty("user.dir"))
        val vectorsDir = moduleDir.resolve("../../../protocol/test-vectors")
        assertTrue(
            "Test vectors directory not found at ${vectorsDir.absolutePath}",
            vectorsDir.exists()
        )
        vectorsDir
    }

    // MARK: bridge.clipboard

    @Test
    fun `deserialize clipboard vectors`() {
        val content = testVectorsDir.resolve("clipboard.json").readText()
        val root = Json.parseToJsonElement(content).jsonObject
        val valid = root["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        for (vector in valid) {
            val obj = vector.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "unknown"
            val packetJson = obj["packet"]?.jsonObject ?: throw AssertionError("Missing packet in $name")
            val expected = obj["expectedFields"]?.jsonObject ?: throw AssertionError("Missing expectedFields in $name")

            val packet = BridgePacket.deserialize(packetJson.toString())

            assertEquals("type mismatch for $name", "bridge.clipboard", packet.type)
            assertEquals(
                "content mismatch for $name",
                expected["content"]?.jsonPrimitive?.content,
                packet.body["content"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `clipboard factory`() {
        val packet = BridgePacket.clipboard("hello world")
        assertEquals("bridge.clipboard", packet.type)
        assertEquals("hello world", packet.body["content"]?.jsonPrimitive?.content)
        assertTrue(packet.id > 0)
    }

    @Test
    fun `clipboard round trip`() {
        val original = BridgePacket.clipboard("round trip test")
        val serialized = String(original.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals("bridge.clipboard", deserialized.type)
        assertEquals("round trip test", deserialized.body["content"]?.jsonPrimitive?.content)
    }

    // MARK: bridge.clipboard.connect

    @Test
    fun `deserialize clipboard connect vectors`() {
        val content = testVectorsDir.resolve("clipboard-connect.json").readText()
        val root = Json.parseToJsonElement(content).jsonObject
        val valid = root["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        for (vector in valid) {
            val obj = vector.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "unknown"
            val packetJson = obj["packet"]?.jsonObject ?: throw AssertionError("Missing packet in $name")
            val expected = obj["expectedFields"]?.jsonObject ?: throw AssertionError("Missing expectedFields in $name")

            val packet = BridgePacket.deserialize(packetJson.toString())

            assertEquals("type mismatch for $name", "bridge.clipboard.connect", packet.type)
            assertEquals(
                "content mismatch for $name",
                expected["content"]?.jsonPrimitive?.content,
                packet.body["content"]?.jsonPrimitive?.content
            )
            assertEquals(
                "timestamp mismatch for $name",
                expected["timestamp"]?.jsonPrimitive?.long,
                packet.body["timestamp"]?.jsonPrimitive?.long
            )
        }
    }

    @Test
    fun `clipboard connect factory`() {
        val packet = BridgePacket.clipboardConnect("test", 1697300000000)
        assertEquals("bridge.clipboard.connect", packet.type)
        assertEquals("test", packet.body["content"]?.jsonPrimitive?.content)
        assertEquals(1697300000000, packet.body["timestamp"]?.jsonPrimitive?.long)
    }

    @Test
    fun `clipboard connect round trip`() {
        val original = BridgePacket.clipboardConnect("reconnect text", 1697299999500)
        val serialized = String(original.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals("bridge.clipboard.connect", deserialized.type)
        assertEquals("reconnect text", deserialized.body["content"]?.jsonPrimitive?.content)
        assertEquals(1697299999500, deserialized.body["timestamp"]?.jsonPrimitive?.long)
    }

    @Test
    fun `clipboard connect zero timestamp`() {
        val packet = BridgePacket.clipboardConnect("unknown", 0)
        assertEquals(0L, packet.body["timestamp"]?.jsonPrimitive?.long)

        val rt = BridgePacket.deserialize(String(packet.serialize(), Charsets.UTF_8))
        assertEquals(0L, rt.body["timestamp"]?.jsonPrimitive?.long)
    }
}
