package com.bridge.app

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.bluetooth.BluetoothAdapter
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
    private val linkListener: () -> Unit = {
        refreshLinkUi()
        syncLinkPrompt()
    }
    private var linkPrompt: AlertDialog? = null

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
        val s = BridgeService.LinkStatus
        val hasTrustedMac = !TrustStore.getInstance().isEmpty
        return LinkState.resolve(
            isBluetoothOn = s.isBluetoothOn,
            hasTrustedMac = hasTrustedMac,
            isSocketConnected = s.isSocketConnected,
            isVerified = s.isVerified
        )
    }

    private fun onConnectAction() {
        when (currentLinkState()) {
            LinkState.CONNECTED -> Unit // working link, status only
            LinkState.BLUETOOTH_OFF -> requestBluetoothOn()
            LinkState.CONNECTING -> {
                // Socket is up but the peer isn't verified — the trust handshake
                // stalled. Initiate pairing from the phone to re-pin the Mac.
                startBridgeService()
                BridgeService.requestPairing()
                refreshLinkUi()
            }
            LinkState.UNPAIRED, LinkState.DISCONNECTED -> {
                // No socket yet — (re)start BLE advertising so the Mac reconnects;
                // pairing then runs automatically once the link is up.
                startBridgeService()
                BridgeService.requestReconnect()
                refreshLinkUi()
            }
        }
    }

    private fun requestBluetoothOn() {
        // System-managed enable prompt; we never force the adapter on silently.
        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun refreshLinkUi() {
        val state = currentLinkState()
        connectItem?.title = LinkState.actionLabel(state)

        statePill.text = LinkState.pillText(state)
        statePill.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(LinkState.pillColorHex(state)))

        macName.text = when (state) {
            LinkState.CONNECTED -> BridgeService.LinkStatus.connectedPeerName ?: "Mac"
            LinkState.CONNECTING -> BridgeService.LinkStatus.connectedPeerName ?: "Verifying…"
            LinkState.DISCONNECTED -> "Mac paired"
            LinkState.UNPAIRED -> getString(R.string.status_not_paired)
            LinkState.BLUETOOTH_OFF -> "Bluetooth off"
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

    /** On open, if the link isn't actually working, say so plainly and offer the fix. */
    private fun maybePromptLinkIssue() {
        val state = currentLinkState()
        if (!state.needsAttention) {
            linkPrompt?.dismiss()
            linkPrompt = null
            return
        }
        if (linkPrompt?.isShowing == true) {
            applyPromptText(state)
            return
        }

        linkPrompt = AlertDialog.Builder(this)
            .setPositiveButton(LinkState.actionLabel(state)) { _, _ -> onConnectAction() }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { linkPrompt = null }
            .create()
            .also { dialog ->
                applyPromptText(state, dialog)
                dialog.show()
                applyPromptText(state, dialog) // button text needs the shown dialog
            }
    }

    /** Keep an already-open prompt honest as the state changes under it. */
    private fun syncLinkPrompt() {
        val dialog = linkPrompt ?: return
        val state = currentLinkState()
        if (!state.needsAttention) {
            dialog.dismiss()
            linkPrompt = null
        } else {
            applyPromptText(state, dialog)
        }
    }

    private fun applyPromptText(state: LinkState, dialog: AlertDialog? = linkPrompt) {
        dialog ?: return
        dialog.setTitle(LinkState.promptTitle(state))
        dialog.setMessage(LinkState.promptBody(state))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = LinkState.actionLabel(state)
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
        maybePromptLinkIssue()
    }

    override fun onPause() {
        super.onPause()
        BridgeService.clipboardBridge?.activityContext = null
        BridgeService.LinkStatus.removeListener(linkListener)
        linkPrompt?.dismiss()
        linkPrompt = null
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
