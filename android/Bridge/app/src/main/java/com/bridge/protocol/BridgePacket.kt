package com.bridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

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
        private val json = Json { ignoreUnknownKeys = false }

        fun deserialize(line: String): BridgePacket {
            val trimmed = line.trim()
            require(trimmed.isNotEmpty()) { "empty line is not a valid packet" }
            return json.decodeFromString(serializer(), trimmed)
        }

        fun ping(): BridgePacket = BridgePacket(
            id = System.currentTimeMillis(),
            type = "bridge.ping",
            body = buildJsonObject {}
        )
    }
}
