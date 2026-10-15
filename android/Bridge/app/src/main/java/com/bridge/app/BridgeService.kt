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
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.bridge.battery.BatteryMonitor
import com.bridge.ble.BlePeripheral
import com.bridge.ble.BridgeConstants
import com.bridge.notifications.BridgeNotificationListener
import com.bridge.protocol.BridgePacket
import com.bridge.security.CertificateManager
import com.bridge.security.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
    }

    private var blePeripheral: BlePeripheral? = null
    private var batteryMonitor: BatteryMonitor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

        blePeripheral?.setOnPairCompletedHandler {
            batteryMonitor?.sendInitialBattery()
        }
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
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "BridgeService destroyed")
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
