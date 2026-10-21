package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShareRequestPacketTest {

    private val testVectorsDir: File by lazy {
        val moduleDir = File(System.getProperty("user.dir"))
        moduleDir.resolve("../../../protocol/test-vectors")
    }

    @Test
    fun `deserialize share request vectors`() {
        val content = testVectorsDir.resolve("share-request.json").readText()
        val valid = Json.parseToJsonElement(content).jsonObject["valid"]!!.jsonArray

        for (vector in valid) {
            val obj = vector.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val packet = BridgePacket.deserialize(obj["packet"]!!.jsonObject.toString())
            val expected = obj["expectedFields"]!!.jsonObject

            assertEquals("type for $name", "bridge.share.request", packet.type)
            assertEquals(
                "filename for $name",
                expected["filename"]!!.jsonPrimitive.content,
                packet.body["filename"]!!.jsonPrimitive.content
            )
            assertEquals("payloadSize for $name", expected["payloadSize"]!!.jsonPrimitive.long, packet.payloadSize)
            assertEquals("port for $name", expected["port"]!!.jsonPrimitive.content.toInt(), packet.payloadPort)
            assertTrue("hasPayload for $name", packet.hasPayload)
        }
    }

    @Test
    fun `deserialize no-payload vectors`() {
        val content = testVectorsDir.resolve("share-request-no-payload.json").readText()
        val valid = Json.parseToJsonElement(content).jsonObject["valid"]!!.jsonArray

        for (vector in valid) {
            val obj = vector.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val packet = BridgePacket.deserialize(obj["packet"]!!.jsonObject.toString())

            assertEquals("type for $name", "bridge.share.request", packet.type)
            assertEquals("payloadSize for $name", 0L, packet.payloadSize)
            assertNull("port absent for $name", packet.payloadPort)
            assertFalse("no payload for $name", packet.hasPayload)
        }
    }

    @Test
    fun `share request factory with payload`() {
        val packet = BridgePacket.shareRequest("a.png", 204800, 204800, 40001)
        assertEquals("bridge.share.request", packet.type)
        assertEquals("a.png", packet.body["filename"]!!.jsonPrimitive.content)
        assertEquals(204800L, packet.payloadSize)
        assertEquals(40001, packet.payloadPort)
        assertTrue(packet.hasPayload)
    }

    @Test
    fun `share request factory metadata only`() {
        val packet = BridgePacket.shareRequest("empty.txt", 0, 0, null)
        assertEquals(0L, packet.payloadSize)
        assertNull(packet.payloadPort)
        assertFalse(packet.hasPayload)
    }

    @Test
    fun `share request round trip`() {
        val original = BridgePacket.shareRequest("doc.pdf", 512000, 512000, 51000)
        val rt = BridgePacket.deserialize(String(original.serialize(), Charsets.UTF_8))
        assertEquals("doc.pdf", rt.body["filename"]!!.jsonPrimitive.content)
        assertEquals(512000L, rt.payloadSize)
        assertEquals(51000, rt.payloadPort)
    }
}
