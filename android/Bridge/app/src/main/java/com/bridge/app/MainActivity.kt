package com.bridge.app

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bridge.notifications.BridgeNotificationListener
import com.bridge.notifications.NotificationFilterActivity
import com.bridge.permissions.PermissionStatus
import com.bridge.permissions.PermissionStatusChecker
import com.bridge.security.TrustStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var statePill: TextView
    private lateinit var macName: TextView
    private lateinit var lastSeen: TextView
    private lateinit var appGlyph: ImageView
    private lateinit var notificationsSection: MaterialCardView
    private lateinit var notifAccessButton: Button
    private lateinit var clipboardStatus: TextView
    private lateinit var sendClipboardButton: Button
    private lateinit var permissionStatus: TextView
    private lateinit var batteryOptButton: Button

    private var connectItem: MenuItem? = null
    private val linkListener: () -> Unit = { refreshLinkUi() }

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startBridgeService()
            } else {
                macName.text = "Permissions required to run Bridge"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        TrustStore.init(applicationContext)

        statePill = findViewById(R.id.state_pill)
        macName = findViewById(R.id.mac_name)
        lastSeen = findViewById(R.id.last_seen)
        appGlyph = findViewById(R.id.app_glyph)
        notificationsSection = findViewById(R.id.notifications_section)
        notifAccessButton = findViewById(R.id.notif_access_button)
        clipboardStatus = findViewById(R.id.clipboard_status)
        sendClipboardButton = findViewById(R.id.send_clipboard_button)
        permissionStatus = findViewById(R.id.permission_status)
        batteryOptButton = findViewById(R.id.battery_opt_button)

        notificationsSection.setOnClickListener {
            startActivity(Intent(this, NotificationFilterActivity::class.java))
        }
        notifAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        sendClipboardButton.setOnClickListener { sendClipboardToMac() }
        batteryOptButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        checkPermissionsAndStart()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        connectItem = menu.findItem(R.id.action_connect)
        refreshLinkUi()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_connect) {
            onConnectAction()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun currentLinkState(): LinkState {
        val hasTrustedMac = !TrustStore.getInstance().isEmpty
        return LinkState.resolve(hasTrustedMac, BridgeService.LinkStatus.isConnected)
    }

    private fun onConnectAction() {
        when (currentLinkState()) {
            LinkState.CONNECTED -> Unit // status only, no-op
            LinkState.UNPAIRED, LinkState.DISCONNECTED -> {
                // Ensure the service is up, then (re)start BLE advertising so the
                // Mac can discover us and run the iter-2 pairing/reconnect handshake.
                startBridgeService()
                BridgeService.requestReconnect()
                refreshLinkUi()
            }
        }
    }

    private fun refreshLinkUi() {
        val state = currentLinkState()
        connectItem?.title = LinkState.actionLabel(state)

        val (pillText, pillColor) = when (state) {
            LinkState.CONNECTED -> getString(R.string.status_connected) to Color.parseColor("#4CAF50")
            LinkState.DISCONNECTED -> getString(R.string.status_disconnected) to Color.parseColor("#FF9800")
            LinkState.UNPAIRED -> getString(R.string.status_not_paired) to Color.parseColor("#9E9E9E")
        }
        statePill.text = pillText
        statePill.backgroundTintList = android.content.res.ColorStateList.valueOf(pillColor)

        macName.text = when (state) {
            LinkState.CONNECTED ->
                BridgeService.LinkStatus.connectedPeerName ?: "Mac"
            LinkState.DISCONNECTED -> "Mac paired"
            LinkState.UNPAIRED -> getString(R.string.status_not_paired)
        }

        lastSeen.text = when {
            state == LinkState.CONNECTED -> "Connected now"
            BridgeService.LinkStatus.lastConnectedAt > 0L -> "Last seen " + DateUtils.getRelativeTimeSpanString(
                BridgeService.LinkStatus.lastConnectedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            else -> getString(R.string.status_last_seen_never)
        }
    }

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBridgeService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBridgeService() {
        startForegroundService(Intent(this, BridgeService::class.java))
        checkNotificationAccess()
    }

    override fun onResume() {
        super.onResume()
        BridgeService.clipboardBridge?.activityContext = this
        BridgeService.LinkStatus.addListener(linkListener)
        checkNotificationAccess()
        updateClipboardStatus()
        updatePermissionStatus()
        refreshLinkUi()
    }

    override fun onPause() {
        super.onPause()
        BridgeService.clipboardBridge?.activityContext = null
        BridgeService.LinkStatus.removeListener(linkListener)
    }

    private fun updatePermissionStatus() {
        val checker = PermissionStatusChecker(this)
        permissionStatus.text = checker.all().joinToString("\n") { "${it.name}: ${it.status.label}" }
        batteryOptButton.visibility =
            if (checker.batteryOptimization() == PermissionStatus.GRANTED) View.GONE else View.VISIBLE
    }

    private fun updateClipboardStatus() {
        val appOps = getSystemService(AppOpsManager::class.java)
        val hasBgAccess = appOps.unsafeCheckOpNoThrow(
            "android:read_clipboard",
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED

        if (hasBgAccess) {
            clipboardStatus.text = "Both directions"
            clipboardStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            clipboardStatus.text = "Mac to phone only\nRun scripts/adb-grant-clipboard.sh for full sync"
            clipboardStatus.setTextColor(Color.parseColor("#FF9800"))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            BridgeService.clipboardBridge?.sendClipboardFromActivity(this)
        }
    }

    private fun sendClipboardToMac() {
        BridgeService.clipboardBridge?.sendClipboardFromActivity(this)
    }

    private fun checkNotificationAccess() {
        val cn = ComponentName(this, BridgeNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val enabled = flat?.contains(cn.flattenToString()) == true
        notifAccessButton.visibility = if (enabled) View.GONE else View.VISIBLE
    }
}
