package com.bridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.bridge.battery.BatteryMonitor
import com.bridge.ble.BlePeripheral
import com.bridge.ble.BridgeConstants
import com.bridge.clipboard.ClipboardBridge
import com.bridge.clipboard.ClipboardCap
import com.bridge.filetransfer.FileReceiver
import com.bridge.filetransfer.FileTransferCoordinator
import com.bridge.filetransfer.TransferNotifier
import com.bridge.notifications.BridgeNotificationListener
import com.bridge.notifications.NotificationFilterStore
import com.bridge.permissions.PermissionStatusChecker
import com.bridge.protocol.BridgePacket
import com.bridge.security.CertificateManager
import com.bridge.security.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
        const val ACTION_SEND_FILE = "com.bridge.ACTION_SEND_FILE"
        const val EXTRA_FILE_PATH = "com.bridge.EXTRA_FILE_PATH"
        const val EXTRA_FILE_NAME = "com.bridge.EXTRA_FILE_NAME"
        private const val PERMISSION_WARNING_NOTIFICATION_ID = 2
        var clipboardBridge: ClipboardBridge? = null
            private set

        @Volatile
        private var activeInstance: BridgeService? = null

        /** Ask the running service to restart the BLE link (Reconnect / Pair action). */
        fun requestReconnect() {
            activeInstance?.let { svc ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { svc.restartBlePeripheral() }
            }
        }

        /** Ask the running service to start phone-initiated pairing on the live link. */
        fun requestPairing() {
            activeInstance?.let { svc ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { svc.blePeripheral?.requestPairing() }
            }
        }

        /**
         * Probe the live link (ping + short timeout). If the Mac is gone the
         * socket is force-closed so the status drops to disconnected and BLE
         * re-advertises. Called when the app is opened to confirm the link is
         * really alive rather than trusting a stale "connected" state.
         */
        fun requestHandshake() {
            activeInstance?.blePeripheral?.probeLink()
        }

    }

    /**
     * Observable link status for the UI (iter-7). Holds the latest BLE
     * connection state plus the time the link last went up, and notifies
     * listeners on the main thread so the connect action can refresh live.
     */
    object LinkStatus {
        @Volatile
        var connectionState: BlePeripheral.ConnectionState = BlePeripheral.ConnectionState.Idle
            private set

        /** Wall-clock millis of the most recent transition to Connected, or 0. */
        @Volatile
        var lastConnectedAt: Long = 0L
            private set

        /** Whether the Bluetooth adapter is currently enabled. */
        @Volatile
        var isBluetoothOn: Boolean = true
            private set

        private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /** An L2CAP socket is open (verified or not). */
        val isSocketConnected: Boolean
            get() = connectionState is BlePeripheral.ConnectionState.Connected

        /** The open link is to a trusted, verified peer — the only state that delivers. */
        val isVerified: Boolean
            get() = (connectionState as? BlePeripheral.ConnectionState.Connected)?.verified == true

        /** Paired Mac name while connected, else null. */
        val connectedPeerName: String?
            get() = (connectionState as? BlePeripheral.ConnectionState.Connected)?.deviceName

        fun addListener(listener: () -> Unit) = listeners.add(listener)
        fun removeListener(listener: () -> Unit) = listeners.remove(listener)

        internal fun update(state: BlePeripheral.ConnectionState) {
            connectionState = state
            if (state is BlePeripheral.ConnectionState.Connected && state.verified) {
                lastConnectedAt = System.currentTimeMillis()
            }
            notifyListeners()
        }

        internal fun setBluetoothOn(on: Boolean) {
            if (isBluetoothOn != on) {
                isBluetoothOn = on
                notifyListeners()
            }
        }

        private fun notifyListeners() {
            mainHandler.post { listeners.forEach { it() } }
        }
    }

    private var blePeripheral: BlePeripheral? = null
    private var batteryMonitor: BatteryMonitor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val clipboardTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val testText = intent.getStringExtra("text")
            if (testText != null) {
                Log.i(TAG, "Clipboard test: setting and sending '$testText'")
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Bridge", testText))
                blePeripheral?.sendPacket(BridgePacket.clipboard(ClipboardCap.apply(testText).text))
            } else {
                Log.i(TAG, "Clipboard test broadcast received")
                clipboardBridge?.sendCurrentClipboard()
            }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth turned ON — restarting BLE peripheral")
                    LinkStatus.setBluetoothOn(true)
                    restartBlePeripheral()
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth turned OFF — stopping BLE peripheral")
                    LinkStatus.setBluetoothOn(false)
                    blePeripheral?.stop()
                    blePeripheral = null
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BridgeService created")
        activeInstance = this
        LinkStatus.setBluetoothOn(
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
        )

        createNotificationChannel()
        startForeground(
            BridgeConstants.NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        CertificateManager.init(this)
        TrustStore.init(this)

        try {
            CertificateManager.getInstance().ensureCertificate()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init certificate", e)
        }

        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            RECEIVER_NOT_EXPORTED
        )

        blePeripheral = BlePeripheral(this, ::onConnectionStateChanged)
        blePeripheral?.start()

        com.bridge.notifications.NotificationFilterStore.init(this)

        BridgeNotificationListener.packetSender = { packet ->
            blePeripheral?.sendPacket(packet)
        }

        blePeripheral?.setNotificationReplyHandler { requestReplyId, message ->
            handleNotificationReply(requestReplyId, message)
        }
        blePeripheral?.setNotificationRequestHandler { cancel ->
            BridgeNotificationListener.instance?.cancelNotificationByKey(cancel)
                ?: Log.w(TAG, "Cannot cancel notification: listener not active")
        }

        batteryMonitor = BatteryMonitor(this) { packet ->
            blePeripheral?.sendPacket(packet)
        }
        batteryMonitor?.start(serviceScope)

        clipboardBridge = ClipboardBridge(this) { packet ->
            blePeripheral?.sendPacket(packet)
        }
        clipboardBridge?.start()

        blePeripheral?.setClipboardHandler { packet ->
            clipboardBridge?.handleClipboardPacket(packet)
        }
        blePeripheral?.setClipboardConnectHandler { packet ->
            clipboardBridge?.handleClipboardConnectPacket(packet)
        }

        blePeripheral?.setShareRequestHandler { packet ->
            FileReceiver(applicationContext, TransferNotifier(applicationContext))
                .receive(serviceScope, packet)
        }

        blePeripheral?.setOnPairCompletedHandler {
            batteryMonitor?.sendInitialBattery()
            // On a first pairing the connect handler runs before trust exists and the
            // Mac drops the packet, leaving it on "unknown" all session.
            sendNotificationFilterState()
        }
        registerReceiver(
            clipboardTestReceiver,
            IntentFilter("com.bridge.SEND_CLIPBOARD"),
            RECEIVER_EXPORTED
        )

        blePeripheral?.setOnConnectHandler {
            Log.i(TAG, "onConnectHandler fired, sending clipboard.connect")
            clipboardBridge?.onConnectionReady()
            val connectPacket = clipboardBridge?.makeConnectPacket()
            if (connectPacket != null) {
                blePeripheral?.sendPacket(connectPacket)
            }
            sendNotificationFilterState()
        }

        // Keep the Mac's mirroring count honest as the user toggles apps.
        serviceScope.launch {
            NotificationFilterStore.init(applicationContext).state.collect {
                sendNotificationFilterState()
            }
        }

        checkPermissionsOnStart()
    }

    private fun sendNotificationFilterState() {
        val state = NotificationFilterStore.init(applicationContext).state.value
        blePeripheral?.sendPacket(
            BridgePacket.notificationFilter(
                allowed = state.allowed.size,
                seen = state.seen.size,
                muted = state.masterMute
            )
        )
    }

    private fun handleNotificationReply(requestReplyId: String, message: String) {
        val cached = BridgeNotificationListener.getCachedAction(requestReplyId)
        if (cached == null) {
            Log.d(TAG, "Stale requestReplyId: $requestReplyId — dropping reply")
            return
        }

        try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in cached.remoteInputs) {
                bundle.putCharSequence(ri.resultKey, message)
            }
            android.app.RemoteInput.addResultsToIntent(cached.remoteInputs, intent, bundle)
            cached.action.actionIntent.send(this, 0, intent)
            Log.i(TAG, "Reply delivered for $requestReplyId")

            // Deliberately does NOT cancel the notification. Cancelling fired
            // onNotificationRemoved, which evicted the cached RemoteInput, so a second
            // reply to the same conversation was dropped — the bug the non-consuming
            // cache read was meant to fix. The originating app owns its notification's
            // lifetime after a reply, and whatever it does now reaches the Mac through
            // the dismissal packet.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deliver reply", e)
        }
    }

    private fun onConnectionStateChanged(state: BlePeripheral.ConnectionState) {
        Log.i(TAG, "Connection state: $state")
        BridgeService.LinkStatus.update(state)
        val str = state.toString()
        if (str.contains("Disconnected") || str.contains("Advertising")) {
            clipboardBridge?.onConnectionLost()
        }
    }

    private fun restartBlePeripheral() {
        blePeripheral?.stop()
        blePeripheral = BlePeripheral(this, ::onConnectionStateChanged)
        blePeripheral?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BridgeService onStartCommand")
        if (intent?.action == ACTION_SEND_FILE) {
            val path = intent.getStringExtra(EXTRA_FILE_PATH)
            val name = intent.getStringExtra(EXTRA_FILE_NAME) ?: "file"
            if (path != null) sendSharedFile(path, name)
        }
        return START_STICKY
    }

    private fun sendSharedFile(path: String, name: String) {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Shared file missing or empty: $path")
            return
        }
        Log.i(TAG, "Sending shared file $name (${file.length()} bytes)")
        val nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager
        val notifier = TransferNotifier(applicationContext)
        val coordinator = FileTransferCoordinator(nsd, notifier) { blePeripheral?.sendPacket(it) }
        // Coordinator streams the file off disk and deletes it when done.
        coordinator.sendFile(serviceScope, name, file, sharedNetworkAvailable = isWifiConnected())
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun checkPermissionsOnStart() {
        val checker = PermissionStatusChecker(this)
        if (checker.notificationAccess() != com.bridge.permissions.PermissionStatus.GRANTED) {
            postPermissionWarning()
        }
    }

    private fun postPermissionWarning() {
        val notification = Notification.Builder(this, BridgeConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Bridge cannot mirror notifications")
            .setContentText("Tap to grant Notification Access.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(PERMISSION_WARNING_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.i(TAG, "BridgeService destroyed")
        try { unregisterReceiver(clipboardTestReceiver) } catch (_: Exception) {}
        clipboardBridge?.stop()
        clipboardBridge = null
        batteryMonitor?.stop()
        batteryMonitor = null
        unregisterReceiver(bluetoothStateReceiver)
        blePeripheral?.stop()
        blePeripheral = null
        LinkStatus.update(BlePeripheral.ConnectionState.Idle)
        activeInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        // iter-7: minimal, silent channel for the required FGS notification.
        val minChannel = NotificationChannel(
            BridgeConstants.NOTIFICATION_CHANNEL_ID_MIN,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps Bridge running in the background"
            setShowBadge(false)
        }
        manager.createNotificationChannel(minChannel)

        // Retain a LOW channel for actionable alerts (e.g. the permission warning).
        val alertChannel = NotificationChannel(
            BridgeConstants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(alertChannel)
    }

    private fun buildNotification(): Notification {
        // Icon-only, no title/text: the quietest form Android allows for a
        // required connectedDevice foreground-service notification.
        return Notification.Builder(this, BridgeConstants.NOTIFICATION_CHANNEL_ID_MIN)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
