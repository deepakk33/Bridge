package com.bridge.protocol

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PingWatchdog(
    private val pingIntervalMs: Long = 30_000,
    private val watchdogTimeoutMs: Long = 60_000
) {
    companion object {
        private const val TAG = "PingWatchdog"
    }

    private var pingJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile
    private var lastPacketTime: Long = 0

    fun start(
        scope: CoroutineScope,
        sendPing: () -> Unit,
        onTimeout: () -> Unit
    ) {
        lastPacketTime = System.currentTimeMillis()

        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(pingIntervalMs)
                sendPing()
            }
        }

        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(pingIntervalMs)
                val elapsed = System.currentTimeMillis() - lastPacketTime
                if (elapsed > watchdogTimeoutMs) {
                    Log.w(TAG, "Watchdog timeout: no packet for ${elapsed}ms")
                    onTimeout()
                    break
                }
            }
        }

        Log.i(TAG, "Watchdog started (ping=${pingIntervalMs}ms, timeout=${watchdogTimeoutMs}ms)")
    }

    fun stop() {
        pingJob?.cancel()
        pingJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        Log.i(TAG, "Watchdog stopped")
    }

    fun packetReceived() {
        lastPacketTime = System.currentTimeMillis()
    }
}
