package com.bridge.protocol

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PingHandler {

    private const val TAG = "PingHandler"

    fun handle(packet: BridgePacket): BridgePacket {
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
