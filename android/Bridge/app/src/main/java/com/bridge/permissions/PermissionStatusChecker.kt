package com.bridge.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Grant status for a single Android permission / grant. */
enum class PermissionStatus(val label: String) {
    GRANTED("Granted"),
    DENIED("Disabled")
}

/**
 * What a permission row's button does. Pure data — it names the intent to fire, it never
 * holds a Context, so the row list stays testable off-device.
 */
sealed interface PermissionAction {
    val label: String

    /** Granted rows have nothing left to do, so they show no button. */
    data object None : PermissionAction {
        override val label = ""
    }

    data object NotificationAccess : PermissionAction {
        override val label = "Enable"
    }

    data object BatteryOptimization : PermissionAction {
        override val label = "Allow"
    }

    /** A runtime permission: prompt, or fall back to app settings. See [RuntimeRecovery]. */
    data class Runtime(val permissions: List<String>) : PermissionAction {
        override val label = "Grant"
    }
}

/**
 * How to get a denied runtime permission granted again.
 *
 * `shouldShowRequestPermissionRationale` is false both *before* the first prompt and *after*
 * a permanent denial, so it cannot distinguish them alone; whether we have ever asked is the
 * second input that can.
 */
enum class RuntimeRecovery {
    REQUEST,
    OPEN_APP_SETTINGS;

    companion object {
        fun resolve(hasAsked: Boolean, showRationale: Boolean): RuntimeRecovery =
            if (hasAsked && !showRationale) OPEN_APP_SETTINGS else REQUEST
    }
}

/**
 * One row in the Bridge settings permission screen: what it is, whether it is held, what
 * Bridge cannot do without it, and the one action that fixes it.
 */
data class PermissionItem(
    val name: String,
    val status: PermissionStatus,
    val detail: String = "",
    val action: PermissionAction = PermissionAction.None
)

/**
 * Checks every permission / grant Bridge needs: Notification Access, the battery
 * optimisation exclusion, and the runtime permissions requested at first launch. Surfaced in
 * the settings screen and checked on foreground-service start.
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

    fun bluetooth(): PermissionStatus = statusOf(PermissionRows.BLUETOOTH_PERMISSIONS)

    /** Null below API 33, where the permission does not exist and the row must not appear. */
    fun postNotifications(): PermissionStatus? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            statusOf(PermissionRows.POST_NOTIFICATION_PERMISSIONS)
        } else {
            null
        }

    private fun statusOf(permissions: List<String>): PermissionStatus =
        if (permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) PermissionStatus.GRANTED else PermissionStatus.DENIED

    fun all(): List<PermissionItem> = PermissionRows.of(
        notificationAccess(), batteryOptimization(), bluetooth(), postNotifications()
    )
}

/** Pure row builder — the shape of the permission list, testable without a Context. */
object PermissionRows {

    val BLUETOOTH_PERMISSIONS = listOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    val POST_NOTIFICATION_PERMISSIONS = listOf(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * @param postNotifications null below API 33, where the permission does not exist.
     *
     * The consequence lines live here rather than in `strings.xml` because this builder has
     * no Context to resolve a resource with — and because the BR-15 guard asserts on the
     * actual words, which a resource id would hide.
     */
    fun of(
        notificationAccess: PermissionStatus,
        batteryOptimization: PermissionStatus,
        bluetooth: PermissionStatus,
        postNotifications: PermissionStatus?
    ): List<PermissionItem> = buildList {
        add(
            PermissionItem(
                "Notification Access", notificationAccess,
                detail = "Without it, phone notifications never reach the Mac.",
                action = actionOr(notificationAccess, PermissionAction.NotificationAccess)
            )
        )
        add(
            PermissionItem(
                "Battery Optimization", batteryOptimization,
                detail = "Set Bridge to Unrestricted so One UI does not kill the service.",
                action = actionOr(batteryOptimization, PermissionAction.BatteryOptimization)
            )
        )
        add(
            PermissionItem(
                "Bluetooth", bluetooth,
                detail = "Without it, the phone cannot advertise and the Mac never finds it.",
                action = actionOr(bluetooth, PermissionAction.Runtime(BLUETOOTH_PERMISSIONS))
            )
        )
        if (postNotifications != null) {
            add(
                PermissionItem(
                    "Notifications", postNotifications,
                    detail = "Without it, Bridge cannot show transfer or link alerts on the phone.",
                    action = actionOr(
                        postNotifications, PermissionAction.Runtime(POST_NOTIFICATION_PERMISSIONS)
                    )
                )
            )
        }
    }

    private fun actionOr(status: PermissionStatus, action: PermissionAction) =
        if (status == PermissionStatus.GRANTED) PermissionAction.None else action
}

/** Pure summary helper — testable without a Context. */
object PermissionSummary {
    fun allGranted(items: List<PermissionItem>): Boolean =
        items.all { it.status == PermissionStatus.GRANTED }

    fun firstProblem(items: List<PermissionItem>): PermissionItem? =
        items.firstOrNull { it.status != PermissionStatus.GRANTED }
}
