package com.bridge.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/** Grant status for a single Android permission / grant. */
enum class PermissionStatus(val label: String) {
    GRANTED("Granted"),
    DENIED("Disabled"),
    LIMITED("Limited")
}

/** One row in the Bridge settings permission screen. */
data class PermissionItem(
    val name: String,
    val status: PermissionStatus,
    val detail: String? = null
)

/**
 * Checks every permission / grant Bridge needs: Notification Access, Battery
 * Optimization exclusion, and the ADB clipboard grant. Surfaced in the settings
 * screen and checked on foreground-service start.
 */
class PermissionStatusChecker(private val context: Context) {

    companion object {
        const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
        val ADB_GRANT_COMMAND =
            "adb shell pm grant com.bridge.app $READ_CLIPBOARD_IN_BACKGROUND"
    }

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

    fun adbClipboardGrant(): PermissionStatus {
        val granted = context.checkSelfPermission(READ_CLIPBOARD_IN_BACKGROUND) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionStatus.GRANTED else PermissionStatus.LIMITED
    }

    fun all(): List<PermissionItem> = listOf(
        PermissionItem("Notification Access", notificationAccess()),
        PermissionItem("Battery Optimization", batteryOptimization(),
            detail = "Set Bridge to Unrestricted so One UI does not kill the service."),
        PermissionItem("ADB Clipboard", adbClipboardGrant(),
            detail = if (adbClipboardGrant() == PermissionStatus.LIMITED)
                "phone-to-Mac clipboard disabled. Run: $ADB_GRANT_COMMAND" else null)
    )
}

/** Pure summary helper — testable without a Context. */
object PermissionSummary {
    fun allGranted(items: List<PermissionItem>): Boolean =
        items.all { it.status == PermissionStatus.GRANTED }

    fun firstProblem(items: List<PermissionItem>): PermissionItem? =
        items.firstOrNull { it.status != PermissionStatus.GRANTED }
}
