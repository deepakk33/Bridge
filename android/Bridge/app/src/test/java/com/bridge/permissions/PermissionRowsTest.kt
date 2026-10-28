package com.bridge.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BR-15: the permission list may only offer rows the user can actually reach Granted.
 * The clipboard row could not be — READ_CLIPBOARD_IN_BACKGROUND is role-managed on
 * Android 16 — and its detail held the failing adb command verbatim, which BR-10 is about
 * to render on screen. These assertions are what stop it coming back.
 */
class PermissionRowsTest {

    private val rows = PermissionRows.of(PermissionStatus.DENIED, PermissionStatus.DENIED)

    @Test
    fun `only the two obtainable rows are offered`() {
        assertEquals(listOf("Notification Access", "Battery Optimization"), rows.map { it.name })
    }

    @Test
    fun `no row mentions a clipboard grant or an adb command`() {
        val forbidden = listOf("adb", "pm grant", "appops", "clipboard", "read_clipboard")
        for (row in rows) {
            val text = "${row.name} ${row.detail.orEmpty()}".lowercase()
            for (word in forbidden) {
                assertTrue(
                    "Permission row \"${row.name}\" must not mention \"$word\": $text",
                    !text.contains(word)
                )
            }
        }
    }

    @Test
    fun `every row that is not granted carries a status the user can change`() {
        // Both rows are reachable through a system settings screen, so DENIED is the only
        // non-granted status the list can produce. A status with no action behind it is the
        // defect BR-15 removed.
        assertTrue(rows.all { it.status == PermissionStatus.DENIED })
        assertEquals(
            PermissionStatus.entries.toSet(),
            setOf(PermissionStatus.GRANTED, PermissionStatus.DENIED)
        )
    }

    @Test
    fun `granting both clears the list`() {
        val granted = PermissionRows.of(PermissionStatus.GRANTED, PermissionStatus.GRANTED)
        assertTrue(PermissionSummary.allGranted(granted))
    }
}

/**
 * `PermissionRowsTest` asserts over the pure builder, but `PermissionStatusChecker.all()` is
 * what the UI calls and it needs a Context this source set cannot provide (no Robolectric).
 * A row appended directly inside `all()` would therefore escape every assertion above — which
 * is precisely the regression BR-15 exists to prevent. So assert on the source instead: `all()`
 * must do nothing but delegate.
 */
class PermissionCheckerDelegatesTest {

    private val source = sequenceOf(
        "src/main/java/com/bridge/permissions/PermissionStatusChecker.kt",
        "app/src/main/java/com/bridge/permissions/PermissionStatusChecker.kt",
        "android/Bridge/app/src/main/java/com/bridge/permissions/PermissionStatusChecker.kt"
    ).map { File(it) }.firstOrNull { it.exists() }

    @Test
    fun `the source file is found, or this guard is worthless`() {
        assertNotNull(
            "PermissionStatusChecker.kt not found from working dir ${File("").absolutePath}",
            source
        )
    }

    @Test
    fun `all() only delegates to the pure row builder`() {
        val text = source!!.readText()
        val body = text.substringAfter("fun all()").substringBefore("\n}")
        assertTrue(
            "all() must delegate to PermissionRows.of(), but reads: $body",
            body.contains("PermissionRows.of(")
        )
        assertFalse(
            "all() must not build rows itself - add them to PermissionRows.of() so " +
                "PermissionRowsTest can check them. Body: $body",
            body.contains("PermissionItem(")
        )
    }
}
