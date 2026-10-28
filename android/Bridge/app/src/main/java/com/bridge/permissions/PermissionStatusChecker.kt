package com.bridge.permissions

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/** Grant status for a single Android permission / grant. */
enum class PermissionStatus(val label: String) {
    GRANTED("Granted"),
    DENIED("Disabled")
}

/** One row in the Bridge settings permission screen. */
data class PermissionItem(
    val name: String,
    val status: PermissionStatus,
    val detail: String? = null
)

/**
 * Checks every permission / grant Bridge needs: Notification Access and the battery
 * optimisation exclusion. Surfaced in the settings screen and checked on foreground-service
 * start.
 *
 * There is deliberately no clipboard row. Background clipboard read needs
 * READ_CLIPBOARD_IN_BACKGROUND, which Android 16 manages by role and refuses to `pm grant`
 * to a sideloaded build, so the row could only ever sit at a status the user cannot change —
 * and the command it used to offer fails with a SecurityException. Manual phone-to-Mac sync
 * is reported by the clipboard card instead. See BR-15.
 */
class PermissionStatusChecker(private val context: Context) {

    fun notificationAccess(): PermissionStatus {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        return if (enabled.contains(context.packageName)) PermissionStatus.GRANTED
        else PermissionStatus.DENIED
    }

    fun batteryOptimization(): PermissionStatus {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (pm.isIgnoringBatteryOptimizations(context.packageName)) PermissionStatus.GRANTED
        else PermissionStatus.DENIED
    }

    fun all(): List<PermissionItem> = PermissionRows.of(notificationAccess(), batteryOptimization())
}

/** Pure row builder — the shape of the permission list, testable without a Context. */
object PermissionRows {
    fun of(notificationAccess: PermissionStatus, batteryOptimization: PermissionStatus) = listOf(
        PermissionItem("Notification Access", notificationAccess),
        PermissionItem("Battery Optimization", batteryOptimization,
            detail = "Set Bridge to Unrestricted so One UI does not kill the service.")
    )
}

/** Pure summary helper — testable without a Context. */
object PermissionSummary {
    fun allGranted(items: List<PermissionItem>): Boolean =
        items.all { it.status == PermissionStatus.GRANTED }

    fun firstProblem(items: List<PermissionItem>): PermissionItem? =
        items.firstOrNull { it.status != PermissionStatus.GRANTED }
}
