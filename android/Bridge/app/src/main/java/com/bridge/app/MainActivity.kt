package com.bridge.app

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bridge.clipboard.ClipboardCardState
import com.bridge.notifications.BridgeNotificationListener
import com.bridge.notifications.NotificationFilterActivity
import com.bridge.notifications.NotificationFilterStore
import com.bridge.permissions.PermissionAction
import com.bridge.permissions.PermissionStatus
import com.bridge.permissions.PermissionStatusChecker
import com.bridge.permissions.RuntimeRecovery
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
    private lateinit var notifMirroringCount: TextView
    private lateinit var clipboardStatus: TextView
    private var clipboardStatusDefaultColors: android.content.res.ColorStateList? = null
    private lateinit var sendClipboardButton: Button
    private lateinit var permissionRows: LinearLayout

    /** Which runtime permissions we have ever prompted for — see [RuntimeRecovery]. */
    private val askedPrefs by lazy {
        getSharedPreferences("bridge_permissions", MODE_PRIVATE)
    }

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
            }
            // A denial is reported by its own permission row. macName describes the link.
            updatePermissionStatus()
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
        notifMirroringCount = findViewById(R.id.notif_mirroring_count)
        clipboardStatus = findViewById(R.id.clipboard_status)
        clipboardStatusDefaultColors = clipboardStatus.textColors
        sendClipboardButton = findViewById(R.id.send_clipboard_button)
        permissionRows = findViewById(R.id.permission_rows)

        notificationsSection.setOnClickListener {
            startActivity(Intent(this, NotificationFilterActivity::class.java))
        }
        notifAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        sendClipboardButton.setOnClickListener { sendClipboardToMac() }

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
        updateClipboardStatus()
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
            requestRuntime(missing)
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
        // Handshake on open: confirm the link is really alive, not stale "connected".
        if (BridgeService.LinkStatus.isSocketConnected) BridgeService.requestHandshake()
        checkNotificationAccess()
        updateMirroringCount()
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
        permissionRows.removeAllViews()
        for (item in PermissionStatusChecker(this).all()) {
            val row = layoutInflater.inflate(R.layout.view_permission_row, permissionRows, false)
            row.findViewById<View>(R.id.permission_dot).backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        if (item.status == PermissionStatus.GRANTED) R.color.status_granted
                        else R.color.status_denied
                    )
                )
            row.findViewById<TextView>(R.id.permission_name).text = item.name
            row.findViewById<TextView>(R.id.permission_detail).text = item.detail

            val button = row.findViewById<Button>(R.id.permission_action)
            val action = item.action
            if (action == PermissionAction.None) {
                button.visibility = View.GONE
            } else {
                button.text = action.label
                button.setOnClickListener { runPermissionAction(action) }
            }
            permissionRows.addView(row)
        }
    }

    private fun runPermissionAction(action: PermissionAction) {
        when (action) {
            PermissionAction.None -> Unit
            PermissionAction.NotificationAccess ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            PermissionAction.BatteryOptimization -> startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
            is PermissionAction.Runtime -> recoverRuntime(action.permissions)
        }
    }

    /**
     * Android stops showing the prompt after a permanent denial, so a row whose button only
     * ever re-requested would be a dead end. Fall through to app settings when that happens.
     */
    private fun recoverRuntime(permissions: List<String>) {
        val recovery = RuntimeRecovery.resolve(
            hasAsked = permissions.all { askedPrefs.getBoolean("has_asked_$it", false) },
            showRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
        )
        when (recovery) {
            RuntimeRecovery.REQUEST -> requestRuntime(permissions)
            RuntimeRecovery.OPEN_APP_SETTINGS -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun requestRuntime(permissions: List<String>) {
        askedPrefs.edit().apply {
            permissions.forEach { putBoolean("has_asked_$it", true) }
        }.apply()
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updateClipboardStatus() {
        val bridge = BridgeService.clipboardBridge
        val state = ClipboardCardState.of(
            isWatching = bridge?.isWatching == true,
            isLinkUp = currentLinkState() == LinkState.CONNECTED,
            lastSend = bridge?.lastSend
        )

        clipboardStatus.text = when (state.kind) {
            ClipboardCardState.Kind.LINK_DOWN -> getString(R.string.clipboard_link_down)
            ClipboardCardState.Kind.MANUAL_NO_GRANT -> getString(R.string.clipboard_manual_no_grant)
            ClipboardCardState.Kind.TRUNCATED ->
                getString(R.string.clipboard_truncated, state.formatArg)
            ClipboardCardState.Kind.SYNCING -> getString(R.string.clipboard_syncing)
        }

        if (state.isWarning) {
            clipboardStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
        } else {
            clipboardStatusDefaultColors?.let { clipboardStatus.setTextColor(it) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Flush only. Focus is not consent: opening the app must not ship the clipboard.
        if (hasFocus) BridgeService.clipboardBridge?.flushPendingRemote()
    }

    private fun sendClipboardToMac() {
        BridgeService.clipboardBridge?.sendClipboardFrom(this)
        updateClipboardStatus()
    }

    private fun checkNotificationAccess() {
        val cn = ComponentName(this, BridgeNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val enabled = flat?.contains(cn.flattenToString()) == true
        notifAccessButton.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    /**
     * States how many apps are mirroring, so a phone that is forwarding nothing says so
     * here rather than only inside the filter screen. Paused outranks the count: an
     * allowed app is not mirroring while master mute is on.
     */
    private fun updateMirroringCount() {
        val state = NotificationFilterStore.init(applicationContext).state.value
        notifMirroringCount.text = when {
            state.masterMute -> getString(R.string.mirroring_paused)
            state.allowed.isEmpty() -> getString(R.string.mirroring_none)
            else -> resources.getQuantityString(
                R.plurals.mirroring_app_count, state.allowed.size, state.allowed.size
            )
        }
    }
}
