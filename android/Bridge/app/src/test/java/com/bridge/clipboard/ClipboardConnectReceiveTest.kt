package com.bridge.clipboard

import android.content.ContextWrapper
import com.bridge.protocol.BridgePacket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Since BR-16 the phone's reconnect packet carries a timestamp and no clipboard text. The
 * receiving side must ignore such a packet rather than read the missing `content` as an empty
 * clip — doing the latter would wipe the clipboard on every reconnect.
 */
class ClipboardConnectReceiveTest {

    private val vectorsDir: File by lazy {
        File(System.getProperty("user.dir")).resolve("../../../protocol/test-vectors")
    }

    private fun vector(name: String): BridgePacket {
        val root = Json.parseToJsonElement(
            vectorsDir.resolve("clipboard-connect.json").readText()
        ).jsonObject
        val match = root["valid"]!!.jsonArray.first {
            it.jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return BridgePacket.deserialize(match.jsonObject["packet"]!!.jsonObject.toString())
    }

    /** No clipboard service is reachable in a JVM test; the path under test must not need one. */
    private fun bridge() = ClipboardBridge(ContextWrapper(null)) { }

    @Test
    fun `a timestamp-only packet is ignored, not read as an empty clip`() {
        val packet = vector("clipboard_connect_timestamp_only")
        assertFalse("the vector must carry no content key", packet.body.containsKey("content"))

        val bridge = bridge()
        val before = bridge.lastClipboardTimestamp

        // Returns before the clipboard is touched. Were the missing content read as "", this
        // would throw on the absent clipboard service instead of returning quietly.
        bridge.handleClipboardConnectPacket(packet)

        assertEquals(
            "an ignored packet must not advance the convergence timestamp",
            before,
            bridge.lastClipboardTimestamp
        )
    }

    @Test
    fun `the timestamp-only vector still parses as a connect packet`() {
        val packet = vector("clipboard_connect_timestamp_only")
        assertEquals("bridge.clipboard.connect", packet.type)
        assertNotNull(packet.body["timestamp"])
    }
}
