package com.bridge.ble

import com.bridge.protocol.BridgePacket
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The service owns one [BleHandlers] for its whole life and hands the same instance to every
 * peripheral it builds. These tests pin that: a rebuild is a new reader of the same holder, not
 * a new registration. BR-16 was a rebuild that re-registered one handler out of nine.
 */
class BleHandlersTest {

    private fun packet(type: String) =
        BridgePacket(id = 1, type = type, body = buildJsonObject {})

    @Test
    fun `a fresh holder has nothing registered`() {
        val handlers = BleHandlers()
        assertNull(handlers.onNotificationReply)
        assertNull(handlers.onNotificationRequest)
        assertNull(handlers.onClipboardReceived)
        assertNull(handlers.onClipboardConnectReceived)
        assertNull(handlers.onShareRequest)
        assertNull(handlers.onPairCompleted)
        assertNull(handlers.onPairConfirmationRequired)
        assertNull(handlers.onPairConfirmationCleared)
        assertNull(handlers.onConnect)
    }

    @Test
    fun `every handler survives a peripheral rebuild`() {
        val fired = mutableListOf<String>()
        val handlers = BleHandlers().apply {
            onNotificationReply = { id, message -> fired += "reply:$id:$message" }
            onNotificationRequest = { cancel -> fired += "cancel:$cancel" }
            onClipboardReceived = { fired += "clipboard:${it.type}" }
            onClipboardConnectReceived = { fired += "clipboardConnect:${it.type}" }
            onShareRequest = { fired += "share:${it.type}" }
            onPairCompleted = { fired += "pairCompleted" }
            onPairConfirmationRequired = { name, code -> fired += "confirm:$name:$code" }
            onPairConfirmationCleared = { fired += "confirmCleared" }
            onConnect = { fired += "connect" }
        }

        // A rebuild replaces every reader of the holder, never the holder itself.
        repeat(2) { generation ->
            val reader: BleHandlers = handlers
            fired.clear()

            reader.onNotificationReply?.invoke("r1", "hi")
            reader.onNotificationRequest?.invoke("key1")
            reader.onClipboardReceived?.invoke(packet("bridge.clipboard"))
            reader.onClipboardConnectReceived?.invoke(packet("bridge.clipboard.connect"))
            reader.onShareRequest?.invoke(packet("bridge.share.request"))
            reader.onPairCompleted?.invoke()
            reader.onPairConfirmationRequired?.invoke("MacBook Air", "123456")
            reader.onPairConfirmationCleared?.invoke()
            reader.onConnect?.invoke()

            assertEquals(
                "generation $generation lost a handler",
                listOf(
                    "reply:r1:hi",
                    "cancel:key1",
                    "clipboard:bridge.clipboard",
                    "clipboardConnect:bridge.clipboard.connect",
                    "share:bridge.share.request",
                    "pairCompleted",
                    "confirm:MacBook Air:123456",
                    "confirmCleared",
                    "connect"
                ),
                fired.toList()
            )
        }
    }
}
