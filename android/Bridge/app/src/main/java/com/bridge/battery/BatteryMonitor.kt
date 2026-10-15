package com.bridge.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.bridge.protocol.BridgePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class BatteryMonitor(
    private val context: Context,
    private val sendPacket: (BridgePacket) -> Unit
) {
    companion object {
        private const val TAG = "BatteryMonitor"
        private const val PERIODIC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val LEVEL_CHANGE_THRESHOLD = 10
    }

    private var lastSentCharge: Int = -1
    private var lastSentCharging: Boolean? = null
    private var periodicJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

            val currentCharge = if (scale > 0) (level * 100) / scale else 0
            val isCharging = plugged != 0

            val chargingChanged = isCharging != lastSentCharging
            val levelChanged = abs(currentCharge - lastSentCharge) >= LEVEL_CHANGE_THRESHOLD

            if (chargingChanged || levelChanged) {
                sendBatteryPacket(currentCharge, isCharging)
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val (charge, charging) = readCurrentBattery()
            sendBatteryPacket(charge, charging)
        }
    }

    fun start(scope: CoroutineScope) {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_NOT_EXPORTED)

        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(powerReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED)

        periodicJob = scope.launch {
            while (isActive) {
                val (charge, charging) = readCurrentBattery()
                sendBatteryPacket(charge, charging)
                delay(PERIODIC_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Battery monitor started")
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        Log.i(TAG, "Battery monitor stopped")
    }

    fun sendInitialBattery() {
        val (charge, charging) = readCurrentBattery()
        sendBatteryPacket(charge, charging)
    }

    private fun sendBatteryPacket(currentCharge: Int, isCharging: Boolean) {
        lastSentCharge = currentCharge
        lastSentCharging = isCharging
        val packet = BridgePacket.battery(currentCharge, isCharging)
        sendPacket(packet)
        Log.d(TAG, "Sent battery: $currentCharge%, charging=$isCharging")
    }

    private fun readCurrentBattery(): Pair<Int, Boolean> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return Pair(level, charging)
    }
}
