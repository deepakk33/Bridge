package com.bridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import com.bridge.protocol.BridgePacket
import com.bridge.protocol.PingHandler
import com.bridge.protocol.PingWatchdog
import com.bridge.security.CertificateManager
import com.bridge.security.PairingManager
import com.bridge.security.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

class L2capServer(
    adapter: BluetoothAdapter,
    private val deviceId: String,
    private val onConnectionChanged: (connected: Boolean) -> Unit
) {
    var onNotificationReply: ((requestReplyId: String, message: String) -> Unit)? = null
    var onNotificationRequest: ((cancel: String) -> Unit)? = null
    var onBatteryReceived: ((currentCharge: Int, isCharging: Boolean) -> Unit)? = null
    var onPairCompleted: (() -> Unit)? = null

    companion object {
        private const val TAG = "L2capServer"
    }

    private val serverSocket: BluetoothServerSocket =
        adapter.listenUsingInsecureL2capChannel()

    val psm: Int = serverSocket.psm

    private var activeSocket: BluetoothSocket? = null
    private var pairingManager: PairingManager? = null
    private val pingWatchdog = PingWatchdog()

    private var peerDeviceId: String? = null
    private var peerFingerprint: String? = null

    suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        Log.i(TAG, "L2CAP accept loop started on PSM=$psm")
        while (isActive) {
            try {
                val socket = serverSocket.accept()
                Log.i(TAG, "L2CAP connection accepted")
                activeSocket = socket
                onConnectionChanged(true)
                handleConnection(socket)
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Accept failed", e)
                }
                break
            } finally {
                activeSocket = null
                peerDeviceId = null
                peerFingerprint = null
                pairingManager?.reset()
                pairingManager = null
                pingWatchdog.stop()
                onConnectionChanged(false)
            }
        }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val outputStream = socket.outputStream
            currentOutputStream = outputStream

            pairingManager = PairingManager { packet -> send(packet, outputStream) }.also { pm ->
                pm.onPairRequest = { deviceName, fingerprint ->
                    Log.i(TAG, "Auto-accepting pair request from $deviceName (fp: $fingerprint)")
                    pm.acceptPair()
                }
                pm.onPairCompleted = { onPairCompleted?.invoke() }
            }

            sendIdentity(outputStream)

            val scope = CoroutineScope(Dispatchers.IO)
            pingWatchdog.start(
                scope = scope,
                sendPing = { send(BridgePacket.ping(), outputStream) },
                onTimeout = {
                    Log.w(TAG, "Watchdog timeout — closing connection")
                    try { socket.close() } catch (_: IOException) {}
                }
            )

            var line = reader.readLine()
            while (line != null) {
                try {
                    val packet = BridgePacket.deserialize(line)
                    pingWatchdog.packetReceived()
                    handlePacket(packet, outputStream)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse packet: ${e.message}")
                }
                line = reader.readLine()
            }

            Log.i(TAG, "Connection closed")
        } catch (e: IOException) {
            Log.e(TAG, "Connection error", e)
        } finally {
            currentOutputStream = null
            pingWatchdog.stop()
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun handlePacket(packet: BridgePacket, outputStream: OutputStream) {
        if (peerDeviceId != null && peerFingerprint != null) {
            try {
                val trusted = TrustStore.getInstance().isTrusted(peerDeviceId!!, peerFingerprint!!)
                if (!trusted && packet.type != "bridge.identity" && packet.type != "bridge.pair") {
                    Log.w(TAG, "Dropping packet from untrusted device: ${packet.type}")
                    return
                }
            } catch (_: IllegalStateException) {}
        }

        when (packet.type) {
            "bridge.ping" -> {
                val response = PingHandler.handle(packet)
                send(response, outputStream)
            }
            "bridge.identity" -> handleIdentityPacket(packet)
            "bridge.pair" -> {
                pairingManager?.handlePairPacket(packet)
            }
            "bridge.notification.reply" -> {
                val replyId = packet.body["requestReplyId"]?.jsonPrimitive?.content
                val message = packet.body["message"]?.jsonPrimitive?.content
                if (replyId != null && message != null) {
                    onNotificationReply?.invoke(replyId, message)
                } else {
                    Log.w(TAG, "Invalid notification.reply packet")
                }
            }
            "bridge.notification.request" -> {
                val cancel = packet.body["cancel"]?.jsonPrimitive?.content
                if (cancel != null) {
                    onNotificationRequest?.invoke(cancel)
                } else {
                    Log.w(TAG, "Invalid notification.request packet")
                }
            }
            "bridge.notification" -> {
                Log.d(TAG, "Received bridge.notification — not applicable on phone side")
            }
            "bridge.battery" -> {
                Log.d(TAG, "Received bridge.battery — not applicable on phone side")
            }
            else -> Log.w(TAG, "Unknown packet type: ${packet.type}")
        }
    }

    private fun handleIdentityPacket(packet: BridgePacket) {
        val id = packet.body["deviceId"]?.jsonPrimitive?.content
        val name = packet.body["deviceName"]?.jsonPrimitive?.content
        val fp = packet.body["certificateFingerprint"]?.jsonPrimitive?.content

        if (id == null || name == null || fp == null) {
            Log.w(TAG, "Invalid identity packet")
            return
        }

        peerDeviceId = id
        peerFingerprint = fp
        pairingManager?.setPeerIdentity(id, name, fp)
        Log.i(TAG, "Peer identity: $name [$id]")
    }

    private fun sendIdentity(outputStream: OutputStream) {
        try {
            val certManager = CertificateManager.getInstance()
            val fingerprint = certManager.getFingerprint()
            val capabilities = listOf(
                "bridge.ping", "bridge.pair",
                "bridge.notification", "bridge.notification.reply",
                "bridge.notification.request", "bridge.battery"
            )
            val packet = BridgePacket.identity(
                deviceId = deviceId,
                deviceName = Build.MODEL,
                deviceType = "phone",
                protocolVersion = 1,
                certificateFingerprint = fingerprint,
                incomingCapabilities = capabilities,
                outgoingCapabilities = capabilities
            )
            send(packet, outputStream)
            Log.i(TAG, "Sent identity packet")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send identity", e)
        }
    }

    private var currentOutputStream: OutputStream? = null

    fun sendPacket(packet: BridgePacket) {
        val os = currentOutputStream ?: run {
            Log.w(TAG, "Cannot send packet: no active connection")
            return
        }
        send(packet, os)
    }

    private fun send(packet: BridgePacket, outputStream: OutputStream) {
        try {
            val bytes = packet.serialize()
            outputStream.write(bytes)
            outputStream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send packet", e)
        }
    }

    fun close() {
        pingWatchdog.stop()
        try { activeSocket?.close() } catch (_: IOException) {}
        try { serverSocket.close() } catch (_: IOException) {}
    }
}
