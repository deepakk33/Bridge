package com.bridge.protocol

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PingHandler {

    private const val TAG = "PingHandler"

    /**
     * The reply to send, or null when there is nothing to answer — a ping that is
     * itself a reply. Answering a reply would loop at line rate now that the Mac
     * answers too (BR-19).
     */
    fun handle(packet: BridgePacket): BridgePacket? {
        if (packet.body.containsKey("replyTo")) {
            Log.d(TAG, "Received ping reply id=${packet.id}")
            return null
        }
        val latency = System.currentTimeMillis() - packet.id
        Log.d(TAG, "Received ping id=${packet.id}, latency=${latency}ms")
        return pong(packet.id)
    }

    private fun pong(originalId: Long): BridgePacket = BridgePacket(
        id = System.currentTimeMillis(),
        type = "bridge.ping",
        body = buildJsonObject {
            put("replyTo", originalId)
        }
    )
}
