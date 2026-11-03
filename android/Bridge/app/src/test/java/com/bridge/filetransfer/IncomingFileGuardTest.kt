package com.bridge.filetransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import java.io.File

/**
 * The peer picks the filename and the declared size. Neither may steer where the file lands or
 * how much is committed before anything is checked. Kept in step with the Mac's
 * FileReceiveController.safeName / isAcceptableSize via the shared test vectors.
 */
class IncomingFileGuardTest {

    private val vectors by lazy {
        Json.parseToJsonElement(
            File(System.getProperty("user.dir"))
                .resolve("../../../protocol/test-vectors/incoming-file-guard.json")
                .readText()
        ).jsonObject
    }

    @Test
    fun `shared vectors - every name reduces as the Mac reduces it`() {
        for (v in vectors["names"]!!.jsonArray) {
            val o = v.jsonObject
            val case = o["name"]!!.jsonPrimitive.content
            assertEquals(
                "name vector '$case'",
                o["safe"]!!.jsonPrimitive.content,
                IncomingFileGuard.safeName(o["raw"]!!.jsonPrimitive.content)
            )
        }
    }

    @Test
    fun `shared vectors - both platforms agree on which sizes are acceptable`() {
        assertEquals(
            "the cap itself must match the Mac's",
            vectors["maxPayloadBytes"]!!.jsonPrimitive.long,
            IncomingFileGuard.MAX_PAYLOAD_BYTES
        )
        for (v in vectors["sizes"]!!.jsonArray) {
            val o = v.jsonObject
            val case = o["name"]!!.jsonPrimitive.content
            assertEquals(
                "size vector '$case'",
                o["acceptable"]!!.jsonPrimitive.boolean,
                IncomingFileGuard.isAcceptableSize(o["size"]!!.jsonPrimitive.long)
            )
        }
    }

    @Test
    fun `a parent reference cannot escape the download directory`() {
        assertEquals(".zshenv", IncomingFileGuard.safeName("../../.zshenv"))
        assertEquals("passwd", IncomingFileGuard.safeName("../../../etc/passwd"))
    }

    @Test
    fun `an absolute path is reduced to its last component`() {
        assertEquals("passwd", IncomingFileGuard.safeName("/etc/passwd"))
    }

    @Test
    fun `names that reduce to nothing get a fallback`() {
        for (raw in listOf("", "/", "///", "..", ".", "   ")) {
            assertEquals("BridgeFile", IncomingFileGuard.safeName(raw))
        }
    }

    @Test
    fun `ordinary names survive unchanged`() {
        assertEquals("report.pdf", IncomingFileGuard.safeName("report.pdf"))
        assertEquals("holiday photo.jpeg", IncomingFileGuard.safeName("holiday photo.jpeg"))
    }

    @Test
    fun `separators the filesystem would honour are neutralised`() {
        assertFalse(IncomingFileGuard.safeName("a:b.txt").contains(":"))
        assertEquals("file.txt", IncomingFileGuard.safeName("dir\\file.txt"))
    }

    @Test
    fun `an absurdly long name is bounded`() {
        val safe = IncomingFileGuard.safeName("a".repeat(2000) + ".bin")
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(safe.isNotEmpty())
    }

    @Test
    fun `a payload above the cap is refused`() {
        assertFalse(IncomingFileGuard.isAcceptableSize(IncomingFileGuard.MAX_PAYLOAD_BYTES + 1))
        assertFalse(IncomingFileGuard.isAcceptableSize(Long.MAX_VALUE))
    }

    @Test
    fun `a payload within the cap is accepted`() {
        assertTrue(IncomingFileGuard.isAcceptableSize(1))
        assertTrue(IncomingFileGuard.isAcceptableSize(716_800))
        assertTrue(IncomingFileGuard.isAcceptableSize(IncomingFileGuard.MAX_PAYLOAD_BYTES))
    }

    @Test
    fun `an empty or negative payload is not acceptable`() {
        assertFalse(IncomingFileGuard.isAcceptableSize(0))
        assertFalse(IncomingFileGuard.isAcceptableSize(-1))
    }
}
