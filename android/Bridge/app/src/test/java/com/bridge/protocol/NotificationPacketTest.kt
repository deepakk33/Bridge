package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationPacketTest {

    private val testVectorsDir: File by lazy {
        val moduleDir = File(System.getProperty("user.dir"))
        val vectorsDir = moduleDir.resolve("../../../protocol/test-vectors")
        assertTrue(
            "Test vectors directory not found at ${vectorsDir.absolutePath}",
            vectorsDir.exists()
        )
        vectorsDir
    }

    // MARK: bridge.notification

    @Test
    fun `deserialize notification vectors`() {
        val content = testVectorsDir.resolve("notification.json").readText()
        val root = Json.parseToJsonElement(content).jsonObject
        val valid = root["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        for (vector in valid) {
            val obj = vector.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "unknown"
            val packetJson = obj["packet"]?.jsonObject ?: throw AssertionError("Missing packet in $name")
            val expected = obj["expectedFields"]?.jsonObject ?: throw AssertionError("Missing expectedFields in $name")

            val serialized = packetJson.toString()
            val packet = BridgePacket.deserialize(serialized)

            assertEquals("type mismatch for $name", "bridge.notification", packet.type)
            assertEquals(
                "id mismatch for $name",
                expected["id"]?.jsonPrimitive?.content,
                packet.body["id"]?.jsonPrimitive?.content
            )
            assertEquals(
                "appName mismatch for $name",
                expected["appName"]?.jsonPrimitive?.content,
                packet.body["appName"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `notification factory with reply`() {
        val packet = BridgePacket.notification(
            id = "com.test|0|null|1",
            appName = "TestApp",
            title = "Hello",
            text = "World",
            actions = listOf("Reply"),
            requestReplyId = "test-uuid"
        )

        assertEquals("bridge.notification", packet.type)
        assertEquals("com.test|0|null|1", packet.body["id"]?.jsonPrimitive?.content)
        assertEquals("test-uuid", packet.body["requestReplyId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `notification factory without reply`() {
        val packet = BridgePacket.notification(
            id = "com.test|0|null|2",
            appName = "Gmail",
            title = "Email",
            text = "Hello",
            actions = emptyList(),
            requestReplyId = null
        )

        assertEquals("bridge.notification", packet.type)
        assertTrue(
            "requestReplyId should be null",
            packet.body["requestReplyId"] is JsonNull
        )
    }

    @Test
    fun `notification round trip`() {
        val original = BridgePacket.notification(
            id = "com.test|0|null|3",
            appName = "Slack",
            title = "Message",
            text = "Hey",
            actions = listOf("Reply", "Mute"),
            requestReplyId = "abc-123"
        )

        val serialized = String(original.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals("bridge.notification", deserialized.type)
        assertEquals("com.test|0|null|3", deserialized.body["id"]?.jsonPrimitive?.content)
        assertEquals("abc-123", deserialized.body["requestReplyId"]?.jsonPrimitive?.content)
        val actions = deserialized.body["actions"]?.jsonArray
        assertNotNull(actions)
        assertEquals(2, actions!!.size)
    }

    // MARK: bridge.notification.reply

    @Test
    fun `deserialize notification reply vectors`() {
        val content = testVectorsDir.resolve("notification-reply.json").readText()
        val root = Json.parseToJsonElement(content).jsonObject
        val valid = root["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        for (vector in valid) {
            val obj = vector.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "unknown"
            val packetJson = obj["packet"]?.jsonObject ?: throw AssertionError("Missing packet")
            val expected = obj["expectedFields"]?.jsonObject ?: throw AssertionError("Missing expectedFields")

            val packet = BridgePacket.deserialize(packetJson.toString())

            assertEquals("type mismatch for $name", "bridge.notification.reply", packet.type)
            assertEquals(
                "requestReplyId mismatch for $name",
                expected["requestReplyId"]?.jsonPrimitive?.content,
                packet.body["requestReplyId"]?.jsonPrimitive?.content
            )
            assertEquals(
                "message mismatch for $name",
                expected["message"]?.jsonPrimitive?.content,
                packet.body["message"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `notification reply factory`() {
        val packet = BridgePacket.notificationReply("uuid-1", "Yes!")
        assertEquals("bridge.notification.reply", packet.type)
        assertEquals("uuid-1", packet.body["requestReplyId"]?.jsonPrimitive?.content)
        assertEquals("Yes!", packet.body["message"]?.jsonPrimitive?.content)
    }

    // MARK: bridge.notification.request

    @Test
    fun `deserialize notification request vectors`() {
        val content = testVectorsDir.resolve("notification-request.json").readText()
        val root = Json.parseToJsonElement(content).jsonObject
        val valid = root["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        for (vector in valid) {
            val obj = vector.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "unknown"
            val packetJson = obj["packet"]?.jsonObject ?: throw AssertionError("Missing packet")
            val expected = obj["expectedFields"]?.jsonObject ?: throw AssertionError("Missing expectedFields")

            val packet = BridgePacket.deserialize(packetJson.toString())

            assertEquals("type mismatch for $name", "bridge.notification.request", packet.type)
            assertEquals(
                "cancel mismatch for $name",
                expected["cancel"]?.jsonPrimitive?.content,
                packet.body["cancel"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `notification request factory`() {
        val packet = BridgePacket.notificationRequest("com.test|0|null|99")
        assertEquals("bridge.notification.request", packet.type)
        assertEquals("com.test|0|null|99", packet.body["cancel"]?.jsonPrimitive?.content)
    }

    // MARK: bridge.battery

    @Test
    fun `deserialize battery vectors`() {
        val content = testVectorsDir.resolve("battery.json").readText()
        val root = Json.parseToJsonElement(content).jsonObject
        val valid = root["valid"]?.jsonArray ?: throw AssertionError("Missing 'valid'")

        for (vector in valid) {
            val obj = vector.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: "unknown"
            val packetJson = obj["packet"]?.jsonObject ?: throw AssertionError("Missing packet")
            val expected = obj["expectedFields"]?.jsonObject ?: throw AssertionError("Missing expectedFields")

            val packet = BridgePacket.deserialize(packetJson.toString())

            assertEquals("type mismatch for $name", "bridge.battery", packet.type)
            assertEquals(
                "currentCharge mismatch for $name",
                expected["currentCharge"]?.jsonPrimitive?.int,
                packet.body["currentCharge"]?.jsonPrimitive?.int
            )
            assertEquals(
                "isCharging mismatch for $name",
                expected["isCharging"]?.jsonPrimitive?.boolean,
                packet.body["isCharging"]?.jsonPrimitive?.boolean
            )
        }
    }

    @Test
    fun `battery factory`() {
        val packet = BridgePacket.battery(85, true)
        assertEquals("bridge.battery", packet.type)
        assertEquals(85, packet.body["currentCharge"]?.jsonPrimitive?.int)
        assertEquals(true, packet.body["isCharging"]?.jsonPrimitive?.boolean)

        val rt = BridgePacket.deserialize(String(packet.serialize(), Charsets.UTF_8))
        assertEquals(85, rt.body["currentCharge"]?.jsonPrimitive?.int)
    }

    @Test
    fun `battery round trip`() {
        val original = BridgePacket.battery(42, false)
        val serialized = String(original.serialize(), Charsets.UTF_8)
        val deserialized = BridgePacket.deserialize(serialized)

        assertEquals("bridge.battery", deserialized.type)
        assertEquals(42, deserialized.body["currentCharge"]?.jsonPrimitive?.int)
        assertEquals(false, deserialized.body["isCharging"]?.jsonPrimitive?.boolean)
    }

    // --- BR-6: bridge.notification.filter ---

    @Test
    fun `notificationFilter carries the counts and mute flag`() {
        val packet = BridgePacket.notificationFilter(allowed = 3, seen = 47, muted = false)

        assertEquals("bridge.notification.filter", packet.type)
        assertEquals(3, packet.body["allowed"]?.jsonPrimitive?.int)
        assertEquals(47, packet.body["seen"]?.jsonPrimitive?.int)
        assertEquals(false, packet.body["muted"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `notificationFilter round-trips through the wire format`() {
        val original = BridgePacket.notificationFilter(allowed = 0, seen = 0, muted = true)
        val line = String(original.serialize(), Charsets.UTF_8)
        val back = BridgePacket.deserialize(line)

        assertEquals(original.type, back.type)
        assertEquals(0, back.body["allowed"]?.jsonPrimitive?.int)
        assertEquals(0, back.body["seen"]?.jsonPrimitive?.int)
        assertEquals(true, back.body["muted"]?.jsonPrimitive?.boolean)
    }
}
