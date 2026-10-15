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
import android.os.IBinder
import android.util.Log
import com.bridge.ble.BlePeripheral
import com.bridge.ble.BridgeConstants

class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
    }

    private var blePeripheral: BlePeripheral? = null

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

        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            RECEIVER_NOT_EXPORTED
        )

        blePeripheral = BlePeripheral(this) { state ->
            Log.i(TAG, "Connection state: $state")
        }
        blePeripheral?.start()
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
