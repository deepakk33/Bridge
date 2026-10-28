package com.bridge.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSummaryTest {

    @Test
    fun `all granted true when every item granted`() {
        val items = listOf(
            PermissionItem("A", PermissionStatus.GRANTED),
            PermissionItem("B", PermissionStatus.GRANTED)
        )
        assertTrue(PermissionSummary.allGranted(items))
        assertNull(PermissionSummary.firstProblem(items))
    }

    @Test
    fun `all granted false when one denied`() {
        val items = listOf(
            PermissionItem("A", PermissionStatus.GRANTED),
            PermissionItem("B", PermissionStatus.DENIED)
        )
        assertFalse(PermissionSummary.allGranted(items))
        assertEquals("B", PermissionSummary.firstProblem(items)?.name)
    }

    @Test
    fun `a denied row counts as a problem`() {
        val items = listOf(PermissionItem("Notification Access", PermissionStatus.DENIED))
        assertFalse(PermissionSummary.allGranted(items))
        assertEquals(PermissionStatus.DENIED, PermissionSummary.firstProblem(items)?.status)
    }

    @Test
    fun `status labels`() {
        assertEquals("Granted", PermissionStatus.GRANTED.label)
        assertEquals("Disabled", PermissionStatus.DENIED.label)
    }
}
