package com.bridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import com.bridge.protocol.BridgePacket
import com.bridge.protocol.CappedLineReader
import com.bridge.protocol.PingHandler
import com.bridge.protocol.PingWatchdog
import com.bridge.security.CertificateManager
import com.bridge.security.PacketGate
import com.bridge.security.PairingManager
import com.bridge.security.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.OutputStream

class L2capServer(
    adapter: BluetoothAdapter,
    private val deviceId: String,
    private val handlers: BleHandlers,
    private val onConnectionChanged: (connected: Boolean) -> Unit
) {
    var onBatteryReceived: ((currentCharge: Int, isCharging: Boolean) -> Unit)? = null
    var onLinkReady: (() -> Unit)? = null

    /**
     * Fired once the peer's identity is known (and again when pairing completes),
     * reporting whether the connected peer is trusted and its display name.
     * A socket connection alone is not a usable link — only a verified peer is.
     */
    var onPeerVerified: ((verified: Boolean, peerName: String?) -> Unit)? = null

    companion object {
        private const val TAG = "L2capServer"
    }

    private val serverSocket: BluetoothServerSocket =
        adapter.listenUsingInsecureL2capChannel()

    val psm: Int = serverSocket.psm

    @Volatile
    private var cachedFingerprint: String? = null

    /**
     * This device's own fingerprint — half of the verification code's input. Only a
     * successful read is cached: `by lazy` memoised a failure too, so one keystore
     * error while the service was starting disabled pairing until the process
     * restarted (BR-19).
     */
    private fun localFingerprint(): String? {
        cachedFingerprint?.let { return it }
        return try {
            CertificateManager.getInstance().getFingerprint().also { cachedFingerprint = it }
        } catch (e: Exception) {
            Log.e(TAG, "No local fingerprint — pairing cannot be confirmed yet", e)
            null
        }
    }

    private var activeSocket: BluetoothSocket? = null
    private var pairingManager: PairingManager? = null
    private val pingWatchdog = PingWatchdog()

    /**
     * The peer on the live control connection, or null when nothing is connected or it
     * has not identified itself. The payload connection is pinned to this, never to a
     * device read out of the trust store — the store holds every Mac ever paired.
     * Cleared in the accept loop's `finally`, so a transfer that outlives its connection
     * fails closed rather than pinning a stale id.
     */
    @Volatile
    var peerDeviceId: String? = null
        private set
    private var peerFingerprint: String? = null
    private var peerName: String? = null

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
                peerName = null
                pairingManager?.reset()
                pairingManager = null
                // The peer is gone, so no answer is owed. Without this the confirmation
                // stays pending behind an ongoing (undismissable) notification whose
                // Accept is a silent no-op.
                com.bridge.security.PairConfirmation.clear()
                handlers.onPairConfirmationCleared?.invoke()
                pingWatchdog.stop()
                onConnectionChanged(false)
            }
        }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        try {
            val reader = CappedLineReader(socket.inputStream)
            val outputStream = socket.outputStream
            currentOutputStream = outputStream

            pairingManager = PairingManager(
                localDeviceId = deviceId,
                localFingerprint = ::localFingerprint,
                sendPacket = { packet -> send(packet, outputStream) }
            ).also { pm ->
                // Nothing is pinned without a human. The confirmation goes to the UI and
                // the answer comes back through acceptPair()/rejectPair().
                pm.onPairRequest = { deviceName, fingerprint, code ->
                    Log.i(TAG, "Pair confirmation needed for $deviceName (fp: $fingerprint)")
                    handlers.onPairConfirmationRequired?.invoke(deviceName, code)
                }
                pm.onPairCompleted = {
                    handlers.onPairCompleted?.invoke()
                    // Pairing just pinned the peer → the link is now verified.
                    onPeerVerified?.invoke(true, peerName)
                }
                pm.onFingerprintMismatch = { deviceName ->
                    Log.e(TAG, "Closing link to $deviceName: certificate does not match the pin")
                    try { socket.close() } catch (_: IOException) {}
                }
            }

            sendIdentity(outputStream)
            Log.i(TAG, "Link ready, firing onLinkReady (handler=${onLinkReady != null})")
            onLinkReady?.invoke()

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
        } catch (e: CappedLineReader.LineTooLongException) {
            // Broken or hostile. Resynchronising a stream from an attacker-chosen offset
            // is a parsing problem nobody needs; the link reconnects on its own.
            Log.e(TAG, "Oversized line from peer — closing connection", e)
        } catch (e: IOException) {
            Log.e(TAG, "Connection error", e)
        } finally {
            currentOutputStream = null
            pingWatchdog.stop()
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /** Trigger phone-initiated pairing on the active connection, if any. */
    fun requestPairing() {
        pairingManager?.requestPair()
    }

    /** The user accepted the confirmation raised by [BleHandlers.onPairConfirmationRequired]. */
    fun acceptPairing() {
        pairingManager?.acceptPair()
    }

    /** The user declined. Neither trust store changes. */
    fun rejectPairing() {
        pairingManager?.rejectPair()
    }

    /**
     * Send a ping and, if nothing comes back within [timeoutMs], close the
     * socket so a dead link drops to disconnected instead of showing a stale
     * "connected". No-op when no socket is open.
     */
    fun probeLink(timeoutMs: Long = 3_000) {
        val out = currentOutputStream ?: return
        val sentAt = System.currentTimeMillis()
        try { send(BridgePacket.ping(), out) } catch (_: IOException) {}
        CoroutineScope(Dispatchers.IO).launch {
            delay(timeoutMs)
            if (pingWatchdog.lastPacketAt < sentAt) {
                Log.w(TAG, "Probe: no response in ${timeoutMs}ms — closing dead link")
                try { activeSocket?.close() } catch (_: IOException) {}
            }
        }
    }

    private fun handlePacket(packet: BridgePacket, outputStream: OutputStream) {
        if (!PacketGate.allows(packet.type, peerDeviceId, peerFingerprint) { TrustStore.getInstance() }) {
            Log.w(TAG, "Dropping ${packet.type} from an untrusted or unidentified peer")
            return
        }

        when (packet.type) {
            "bridge.ping" -> {
                PingHandler.handle(packet)?.let { send(it, outputStream) }
            }
            "bridge.identity" -> handleIdentityPacket(packet)
            "bridge.pair" -> {
                pairingManager?.handlePairPacket(packet)
            }
            "bridge.notification.reply" -> {
                val replyId = packet.body["requestReplyId"]?.jsonPrimitive?.content
                val message = packet.body["message"]?.jsonPrimitive?.content
                if (replyId != null && message != null) {
                    handlers.onNotificationReply?.invoke(replyId, message)
                } else {
                    Log.w(TAG, "Invalid notification.reply packet")
                }
            }
            "bridge.notification.request" -> {
                val cancel = packet.body["cancel"]?.jsonPrimitive?.content
                if (cancel != null) {
                    handlers.onNotificationRequest?.invoke(cancel)
                } else {
                    Log.w(TAG, "Invalid notification.request packet")
                }
            }
            "bridge.clipboard" -> {
                handlers.onClipboardReceived?.invoke(packet)
            }
            "bridge.clipboard.connect" -> {
                handlers.onClipboardConnectReceived?.invoke(packet)
            }
            "bridge.share.request" -> {
                handlers.onShareRequest?.invoke(packet)
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
        peerName = name
        pairingManager?.setPeerIdentity(id, name, fp)
        Log.i(TAG, "Peer identity: $name [$id]")

        val trusted = try {
            TrustStore.getInstance().isTrusted(id, fp)
        } catch (_: IllegalStateException) {
            false
        }
        onPeerVerified?.invoke(trusted, name)
    }

    private fun sendIdentity(outputStream: OutputStream) {
        try {
            val certManager = CertificateManager.getInstance()
            val fingerprint = certManager.getFingerprint()
            val capabilities = listOf(
                "bridge.ping", "bridge.pair",
                "bridge.notification", "bridge.notification.reply",
                "bridge.notification.request", "bridge.notification.filter",
                "bridge.battery",
                "bridge.clipboard", "bridge.clipboard.connect",
                "bridge.share.request"
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
        Log.i(TAG, "sendPacket: type=${packet.type}")
        send(packet, os)
    }

    private val writeLock = Any()

    private fun send(packet: BridgePacket, outputStream: OutputStream) {
        try {
            val bytes = packet.serialize()
            synchronized(writeLock) {
                outputStream.write(bytes)
                outputStream.flush()
            }
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
