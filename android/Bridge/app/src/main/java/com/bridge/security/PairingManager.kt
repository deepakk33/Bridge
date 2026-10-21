package com.bridge.security

import android.util.Log
import com.bridge.protocol.BridgePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

class PairingManager(
    private val sendPacket: (BridgePacket) -> Unit
) {
    companion object {
        private const val TAG = "PairingManager"
        private const val TIMEOUT_MS = 30_000L
    }

    enum class State {
        UNPAIRED,
        AWAITING_RESPONSE,
        PAIRED
    }

    var state: State = State.UNPAIRED
        private set

    var pairedDeviceId: String? = null
        private set
    var pairedDeviceName: String? = null
        private set

    private var peerDeviceId: String? = null
    private var peerDeviceName: String? = null
    private var peerFingerprint: String? = null

    private var timeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    var onPairRequest: ((deviceName: String, fingerprint: String) -> Unit)? = null
    var onPairCompleted: (() -> Unit)? = null

    fun setPeerIdentity(deviceId: String, deviceName: String, fingerprint: String) {
        peerDeviceId = deviceId
        peerDeviceName = deviceName
        peerFingerprint = fingerprint

        val trustStore = TrustStore.getInstance()
        if (trustStore.isTrusted(deviceId, fingerprint)) {
            state = State.PAIRED
            pairedDeviceId = deviceId
            pairedDeviceName = deviceName
            Log.i(TAG, "Already paired with $deviceName")
            onPairCompleted?.invoke()
        }
    }

    fun handlePairPacket(packet: BridgePacket) {
        val pairValue = packet.body["pair"]?.jsonPrimitive?.boolean
        if (pairValue == null) {
            Log.w(TAG, "Invalid pair packet: missing 'pair' field")
            return
        }

        if (pairValue) {
            handlePairAcceptOrRequest()
        } else {
            handlePairRejectOrUnpair()
        }
    }

    fun acceptPair() {
        val deviceId = peerDeviceId ?: return
        val deviceName = peerDeviceName ?: return
        val fingerprint = peerFingerprint ?: return

        sendPacket(BridgePacket.pairAccept())
        TrustStore.getInstance().pin(deviceId, fingerprint)
        state = State.PAIRED
        pairedDeviceId = deviceId
        pairedDeviceName = deviceName
        cancelTimeout()
        Log.i(TAG, "Accepted pair from $deviceName")
        onPairCompleted?.invoke()
    }

    fun rejectPair() {
        sendPacket(BridgePacket.pairReject())
        cancelTimeout()
        Log.i(TAG, "Rejected pair request")
    }

    fun unpair() {
        val deviceId = pairedDeviceId ?: return
        sendPacket(BridgePacket.pairReject())
        TrustStore.getInstance().unpin(deviceId)
        state = State.UNPAIRED
        pairedDeviceId = null
        pairedDeviceName = null
        Log.i(TAG, "Unpaired device $deviceId")
    }

    fun reset() {
        cancelTimeout()
        peerDeviceId = null
        peerDeviceName = null
        peerFingerprint = null
        if (state != State.PAIRED) {
            state = State.UNPAIRED
        }
    }

    private fun handlePairAcceptOrRequest() {
        when (state) {
            State.AWAITING_RESPONSE -> {
                val deviceId = peerDeviceId ?: return
                val deviceName = peerDeviceName ?: return
                val fingerprint = peerFingerprint ?: return
                TrustStore.getInstance().pin(deviceId, fingerprint)
                state = State.PAIRED
                pairedDeviceId = deviceId
                pairedDeviceName = deviceName
                cancelTimeout()
                Log.i(TAG, "Pair accepted by $deviceName")
                onPairCompleted?.invoke()
            }
            State.UNPAIRED -> {
                val deviceName = peerDeviceName ?: return
                val fingerprint = peerFingerprint ?: return
                startTimeout()
                Log.i(TAG, "Incoming pair request from $deviceName")
                onPairRequest?.invoke(deviceName, fingerprint)
            }
            State.PAIRED -> {
                Log.i(TAG, "Already paired, re-accepting pair request (peer may have lost trust store)")
                acceptPair()
            }
        }
    }

    private fun handlePairRejectOrUnpair() {
        when (state) {
            State.AWAITING_RESPONSE -> {
                state = State.UNPAIRED
                cancelTimeout()
                Log.i(TAG, "Pair request rejected by peer")
            }
            State.PAIRED -> {
                val deviceId = pairedDeviceId ?: return
                TrustStore.getInstance().unpin(deviceId)
                state = State.UNPAIRED
                pairedDeviceId = null
                pairedDeviceName = null
                Log.i(TAG, "Unpaired by peer")
            }
            State.UNPAIRED -> {
                cancelTimeout()
            }
        }
    }

    private fun startTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            Log.i(TAG, "Pairing timed out")
            rejectPair()
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }
}
