package com.bridge.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
