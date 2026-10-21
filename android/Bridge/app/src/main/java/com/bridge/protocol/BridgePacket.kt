package com.bridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
data class BridgePacket(
    val id: Long,
    val type: String,
    val body: JsonObject
) {

    init {
        require(id > 0) { "id must be a positive timestamp" }
        require(type.isNotBlank()) { "type must not be blank" }
    }

    fun serialize(): ByteArray {
        val json = Json.encodeToString(serializer(), this)
        return "$json\n".toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val strictJson = Json { ignoreUnknownKeys = false }
        private val lenientJson = Json { ignoreUnknownKeys = true }

        fun deserialize(line: String): BridgePacket {
            val trimmed = line.trim()
            require(trimmed.isNotEmpty()) { "empty line is not a valid packet" }
            return strictJson.decodeFromString(serializer(), trimmed)
        }

        fun deserializeLenient(line: String): BridgePacket {
            val trimmed = line.trim()
            require(trimmed.isNotEmpty()) { "empty line is not a valid packet" }
            return lenientJson.decodeFromString(serializer(), trimmed)
        }

        fun ping(): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.ping",
            body = buildJsonObject {}
        )

        fun identity(
            deviceId: String,
            deviceName: String,
            deviceType: String,
            protocolVersion: Int,
            certificateFingerprint: String,
            incomingCapabilities: List<String>,
            outgoingCapabilities: List<String>
        ): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.identity",
            body = buildJsonObject {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("deviceType", deviceType)
                put("protocolVersion", protocolVersion)
                put("certificateFingerprint", certificateFingerprint)
                putJsonArray("incomingCapabilities") {
                    incomingCapabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
                putJsonArray("outgoingCapabilities") {
                    outgoingCapabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
            }
        )

        fun pairRequest(): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.pair",
            body = buildJsonObject { put("pair", true) }
        )

        fun pairAccept(): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.pair",
            body = buildJsonObject { put("pair", true) }
        )

        fun pairReject(): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.pair",
            body = buildJsonObject { put("pair", false) }
        )

        fun notification(
            id: String,
            appName: String,
            title: String,
            text: String,
            actions: List<String>,
            requestReplyId: String?
        ): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.notification",
            body = buildJsonObject {
                put("id", id)
                put("appName", appName)
                put("title", title)
                put("text", text)
                putJsonArray("actions") {
                    actions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
                if (requestReplyId != null) {
                    put("requestReplyId", requestReplyId)
                } else {
                    put("requestReplyId", JsonNull)
                }
            }
        )

        fun notificationReply(
            requestReplyId: String,
            message: String
        ): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.notification.reply",
            body = buildJsonObject {
                put("requestReplyId", requestReplyId)
                put("message", message)
            }
        )

        fun notificationRequest(cancel: String): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.notification.request",
            body = buildJsonObject {
                put("cancel", cancel)
            }
        )

        fun battery(currentCharge: Int, isCharging: Boolean): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.battery",
            body = buildJsonObject {
                put("currentCharge", currentCharge)
                put("isCharging", isCharging)
            }
        )

        fun clipboard(content: String): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.clipboard",
            body = buildJsonObject {
                put("content", content)
            }
        )

        fun clipboardConnect(content: String, timestamp: Long): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.clipboard.connect",
            body = buildJsonObject {
                put("content", content)
                put("timestamp", timestamp)
            }
        )
    }
}
