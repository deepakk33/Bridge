package com.bridge.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-platform guarantee: the Mac suite reads the same vector file, so the two
 * derivations cannot drift apart without one of these suites going red.
 */
class PairingCodeTest {

    private data class Vector(
        val name: String,
        val a: String,
        val b: String,
        val expected: String
    )

    private val vectorFile: File by lazy {
        File(System.getProperty("user.dir") ?: ".")
            .resolve("../../../protocol/test-vectors/pairing-code.json")
    }

    private fun loadVectors(): List<Vector> {
        val root = Json.parseToJsonElement(vectorFile.readText()).jsonObject
        val vectors = root["vectors"]?.jsonArray
            ?: throw AssertionError("pairing-code.json has no `vectors` array")
        return vectors.map { element ->
            val o = element.jsonObject
            Vector(
                name = o["name"]!!.jsonPrimitive.content,
                a = o["fingerprintA"]!!.jsonPrimitive.content,
                b = o["fingerprintB"]!!.jsonPrimitive.content,
                expected = o["expected"]!!.jsonPrimitive.content
            )
        }
    }

    @Test
    fun `every vector matches`() {
        val vectors = loadVectors()
        assertTrue("expected the full vector set", vectors.size >= 8)
        for (v in vectors) {
            assertEquals(v.name, v.expected, PairingCode.derive(v.a, v.b))
        }
    }

    @Test
    fun `always six digits`() {
        for (v in loadVectors()) {
            val code = PairingCode.derive(v.a, v.b)
            assertEquals(v.name, 6, code.length)
            assertTrue("${v.name} produced $code", code.all { it.isDigit() })
        }
    }

    /**
     * The leading-zero vector exists because formatting without padding silently
     * produces a five-digit code that still looks plausible on screen.
     */
    @Test
    fun `leading zero is preserved`() {
        val zero = loadVectors().firstOrNull { it.expected.startsWith("0") }
            ?: throw AssertionError("vector file lost its leading-zero case")
        assertEquals(zero.expected, PairingCode.derive(zero.a, zero.b))
    }

    @Test
    fun `order does not matter`() {
        val a = "a1".repeat(32)
        val b = "b2".repeat(32)
        assertEquals(PairingCode.derive(a, b), PairingCode.derive(b, a))
    }

    @Test
    fun `case does not matter`() {
        val a = "ab".repeat(32)
        val b = "cd".repeat(32)
        assertEquals(PairingCode.derive(a, b), PairingCode.derive(a.uppercase(), b))
    }

    /** The whole point: a different peer certificate must produce different digits. */
    @Test
    fun `different peer gives different code`() {
        val mac = "11".repeat(32)
        val phone = "22".repeat(32)
        val impostor = "33".repeat(32)
        assertNotEquals(PairingCode.derive(mac, phone), PairingCode.derive(mac, impostor))
    }
}
