package com.bridge.security

import android.util.Log
import com.bridge.protocol.BridgePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class PairingManager(
    private val localDeviceId: String,
    private val sendPacket: (BridgePacket) -> Unit
) {
    companion object {
        private const val TAG = "PairingManager"
        private const val TIMEOUT_MS = 30_000L

        /**
         * Crossed initiation: both sides sent a request at once. The lower deviceId
         * stays the initiator, the other yields and accepts, so exactly one accept
         * flows and both sides pin. Equal (or unknown local) ids make both yield,
         * which also converges — both accept and both pin.
         */
        fun yieldsOnCrossedRequest(localDeviceId: String, peerDeviceId: String): Boolean =
            localDeviceId.isEmpty() || localDeviceId >= peerDeviceId
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
        } else if (trustStore.isEmpty) {
            // Symmetric with the Mac: when we hold no pin at all, initiate pairing
            // instead of waiting for a request that a already-paired peer never sends.
            Log.i(TAG, "No trusted device — auto-initiating pairing with $deviceName")
            requestPair()
        } else if (trustStore.pinnedFingerprint(deviceId) != null) {
            Log.w(TAG, "$deviceName [$deviceId] presented a fingerprint that is not the pinned one — initiating re-pairing")
            requestPair()
        } else {
            Log.w(TAG, "Unknown device $deviceName [$deviceId] while other devices are pinned — initiating pairing")
            requestPair()
        }
    }

    /**
     * Phone-initiated pairing. Sends a pair request and awaits the peer's accept,
     * which pins the peer in [handlePairAcceptOrRequest]. Used both automatically
     * (empty trust store) and on demand (user taps Reconnect while unverified).
     */
    fun requestPair() {
        val deviceName = peerDeviceName ?: run {
            Log.w(TAG, "Pair requested before peer identity is known — ignoring")
            return
        }
        if (state == State.AWAITING_RESPONSE) {
            Log.i(TAG, "Pair request to $deviceName already outstanding")
            return
        }
        sendPacket(BridgePacket.pairRequest())
        state = State.AWAITING_RESPONSE
        startTimeout()
        Log.i(TAG, "Sent pair request to $deviceName")
    }

    fun handlePairPacket(packet: BridgePacket) {
        val pairValue = packet.body["pair"]?.jsonPrimitive?.boolean
        if (pairValue == null) {
            Log.w(TAG, "Invalid pair packet: missing 'pair' field")
            return
        }

        if (pairValue) {
            // "request", "accept", or null for peers that predate the field.
            handlePairAcceptOrRequest(packet.body["kind"]?.jsonPrimitive?.contentOrNull)
        } else {
            handlePairRejectOrUnpair()
        }
    }

    fun acceptPair() {
        val deviceId = peerDeviceId
        val deviceName = peerDeviceName
        val fingerprint = peerFingerprint
        if (deviceId == null || deviceName == null || fingerprint == null) {
            Log.w(TAG, "Cannot accept pair: peer identity unknown")
            return
        }

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

    private fun handlePairAcceptOrRequest(kind: String?) {
        val deviceId = peerDeviceId
        val deviceName = peerDeviceName
        val fingerprint = peerFingerprint
        if (deviceId == null || deviceName == null || fingerprint == null) {
            Log.w(TAG, "Pair packet before peer identity — ignoring")
            return
        }

        when (state) {
            State.AWAITING_RESPONSE -> {
                if (kind == "request") {
                    if (!yieldsOnCrossedRequest(localDeviceId, deviceId)) {
                        Log.i(TAG, "Crossed pair request from $deviceName — we stay the initiator, awaiting their accept")
                        return
                    }
                    Log.i(TAG, "Crossed pair request from $deviceName — yielding and accepting theirs")
                    acceptPair()
                    return
                }
                TrustStore.getInstance().pin(deviceId, fingerprint)
                state = State.PAIRED
                pairedDeviceId = deviceId
                pairedDeviceName = deviceName
                cancelTimeout()
                Log.i(TAG, "Pair accepted by $deviceName")
                onPairCompleted?.invoke()
            }
            State.UNPAIRED -> {
                if (kind == "accept") {
                    Log.i(TAG, "Unsolicited pair accept from $deviceName — treating it as a request")
                }
                Log.i(TAG, "Incoming pair request from $deviceName")
                onPairRequest?.invoke(deviceName, fingerprint)
            }
            State.PAIRED -> {
                if (kind == "accept") {
                    Log.i(TAG, "Already paired — ignoring accept from $deviceName")
                    return
                }
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
                Log.i(TAG, "Peer declined or cancelled pairing while unpaired — nothing to do")
            }
        }
    }

    private fun startTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            // Local expiry only. Sending a reject here would make a peer that already
            // pinned us drop that pin, which is how the link ended up half-paired.
            Log.i(TAG, "Pair request timed out with no response — giving up locally")
            if (state == State.AWAITING_RESPONSE) {
                state = State.UNPAIRED
            }
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }
}
