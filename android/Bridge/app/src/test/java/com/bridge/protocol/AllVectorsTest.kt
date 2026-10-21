package com.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-platform guarantee (PRD: "shared test vectors"): every `valid` packet in
 * protocol/test-vectors parses on the Android side. The Mac suite runs the same
 * sweep, so a vector that parses on one platform parses on both.
 */
class AllVectorsTest {

    private val testVectorsDir: File by lazy {
        File(System.getProperty("user.dir")).resolve("../../../protocol/test-vectors")
    }

    @Test
    fun `every valid vector parses`() {
        val files = testVectorsDir.listFiles { f -> f.extension == "json" }
            ?: throw AssertionError("no test vectors at ${testVectorsDir.absolutePath}")
        assertTrue("no test vectors found", files.isNotEmpty())

        var parsed = 0
        for (file in files) {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val valid = root["valid"]?.jsonArray ?: continue // skip malformed.json etc.
            for (vector in valid) {
                val packetJson = vector.jsonObject["packet"]!!.jsonObject.toString()
                val packet = BridgePacket.deserialize(packetJson)
                assertTrue("empty type in ${file.name}", packet.type.isNotBlank())
                assertTrue("bad id in ${file.name}", packet.id > 0)
                parsed++
            }
        }
        assertTrue("expected many vectors, parsed $parsed", parsed > 10)
    }
}
