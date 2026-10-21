package com.bridge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.IntentCompat
import com.bridge.battery.BatteryMonitor
import com.bridge.ble.BlePeripheral
import com.bridge.ble.BridgeConstants
import com.bridge.clipboard.ClipboardBridge
import com.bridge.clipboard.ClipboardTileService
import com.bridge.filetransfer.FileTransferCoordinator
import com.bridge.notifications.BridgeNotificationListener
import com.bridge.permissions.PermissionStatusChecker
import com.bridge.protocol.BridgePacket
import com.bridge.security.CertificateManager
import com.bridge.security.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
        const val ACTION_SEND_FILE = "com.bridge.ACTION_SEND_FILE"
        const val EXTRA_FILE_URI = "com.bridge.EXTRA_FILE_URI"
        private const val PERMISSION_WARNING_NOTIFICATION_ID = 2
        var clipboardBridge: ClipboardBridge? = null
            private set
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
                blePeripheral?.sendPacket(BridgePacket.clipboard(testText))
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
                    restartBlePeripheral()
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth turned OFF — stopping BLE peripheral")
                    blePeripheral?.stop()
                    blePeripheral = null
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BridgeService created")

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

        blePeripheral = BlePeripheral(this) { state ->
            Log.i(TAG, "Connection state: $state")
            val str = state.toString()
            if (str.contains("Disconnected") || str.contains("Advertising")) {
                clipboardBridge?.onConnectionLost()
            }
        }
        blePeripheral?.start()

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

        ClipboardTileService.packetSender = { packet ->
            blePeripheral?.sendPacket(packet)
        }

        blePeripheral?.setClipboardHandler { packet ->
            clipboardBridge?.handleClipboardPacket(packet)
        }
        blePeripheral?.setClipboardConnectHandler { packet ->
            clipboardBridge?.handleClipboardConnectPacket(packet)
        }

        blePeripheral?.setOnPairCompletedHandler {
            batteryMonitor?.sendInitialBattery()
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
        }

        checkPermissionsOnStart()
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

            BridgeNotificationListener.instance?.cancelNotificationByKey(cached.notificationKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deliver reply", e)
        }
    }

    private fun restartBlePeripheral() {
        blePeripheral?.stop()
        blePeripheral = BlePeripheral(this) { state ->
            Log.i(TAG, "Connection state: $state")
        }
        blePeripheral?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BridgeService onStartCommand")
        if (intent?.action == ACTION_SEND_FILE) {
            val uri = IntentCompat.getParcelableExtra(intent, EXTRA_FILE_URI, Uri::class.java)
            if (uri != null) sendSharedFile(uri)
        }
        return START_STICKY
    }

    private fun sendSharedFile(uri: Uri) {
        serviceScope.launch(Dispatchers.IO) {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read shared file", e)
                null
            } ?: return@launch
            val name = queryDisplayName(uri) ?: "file"
            val nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager
            val coordinator = FileTransferCoordinator(nsd) { blePeripheral?.sendPacket(it) }
            coordinator.sendFile(serviceScope, name, bytes, sharedNetworkAvailable = true)
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
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
        ClipboardTileService.packetSender = null
        batteryMonitor?.stop()
        batteryMonitor = null
        unregisterReceiver(bluetoothStateReceiver)
        blePeripheral?.stop()
        blePeripheral = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            BridgeConstants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while Bridge is active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, BridgeConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
