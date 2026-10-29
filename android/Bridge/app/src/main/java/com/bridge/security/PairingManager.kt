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
    private val localFingerprint: String? = null,
    private val trustStore: () -> TrustStoreAccess = { TrustStore.getInstance() },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
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

    /**
     * One side's answer to the confirmation. Nothing is pinned until this device's user
     * and the peer have both accepted, in either order — holding the peer's answer makes
     * "the user has not decided yet" a real state rather than an instant the old code
     * skipped straight past.
     */
    enum class Decision {
        PENDING,
        ACCEPTED,
        DECLINED
    }

    var state: State = State.UNPAIRED
        private set

    var pairedDeviceId: String? = null
        private set
    var pairedDeviceName: String? = null
        private set

    var localDecision: Decision = Decision.PENDING
        private set
    var remoteDecision: Decision = Decision.PENDING
        private set

    private var peerDeviceId: String? = null
    private var peerDeviceName: String? = null
    private var peerFingerprint: String? = null

    private var timeoutJob: Job? = null

    /** Raised when the user must confirm. [code] is shown on both screens. */
    var onPairRequest: ((deviceName: String, fingerprint: String, code: String) -> Unit)? = null
    var onPairCompleted: (() -> Unit)? = null

    /**
     * Raised when a pinned device presents a different certificate. The caller closes the
     * link; the pin stays, so returning that device to service needs a deliberate unpair.
     */
    var onFingerprintMismatch: ((deviceName: String) -> Unit)? = null

    /** The six digits for the peer being confirmed, or null when none can be derived. */
    val verificationCode: String?
        get() {
            val peer = peerFingerprint ?: return null
            val local = localFingerprint ?: return null
            return PairingCode.derive(local, peer)
        }

    fun setPeerIdentity(deviceId: String, deviceName: String, fingerprint: String) {
        peerDeviceId = deviceId
        peerDeviceName = deviceName
        peerFingerprint = fingerprint

        val store = trustStore()
        if (store.isTrusted(deviceId, fingerprint)) {
            state = State.PAIRED
            pairedDeviceId = deviceId
            pairedDeviceName = deviceName
            Log.i(TAG, "Already paired with $deviceName")
            onPairCompleted?.invoke()
            return
        }

        val pinned = store.pinnedFingerprint(deviceId)
        if (pinned != null) {
            // A reinstall or an impostor. Both need a deliberate re-pair, so the link
            // closes and the pin stays — the old code re-requested here, which is the
            // re-pair hole itself.
            Log.e(TAG, "$deviceName [$deviceId] presented $fingerprint but $pinned is pinned — closing")
            onFingerprintMismatch?.invoke(deviceName)
            return
        }

        // An empty trust store is not consent. Unknown devices are confirmed by a human
        // whether or not this phone has paired with anything before.
        Log.i(TAG, "Unknown device $deviceName [$deviceId] — awaiting confirmation")
        presentPairConfirmation(deviceName, fingerprint)
    }

    /**
     * The local user said yes. Tells the peer and pins if the peer has already said yes.
     * Sends `accept` when the peer asked first and `request` otherwise, so the peer can
     * tell an answer from an opening offer.
     */
    fun acceptPair() {
        val deviceName = peerDeviceName ?: run {
            Log.w(TAG, "Pair accepted before peer identity is known — ignoring")
            return
        }
        if (localDecision == Decision.ACCEPTED) {
            Log.i(TAG, "Already accepted pairing with $deviceName")
            return
        }

        localDecision = Decision.ACCEPTED
        val peerAskedFirst = remoteDecision == Decision.ACCEPTED
        sendPacket(if (peerAskedFirst) BridgePacket.pairAccept() else BridgePacket.pairRequest())

        if (pinIfBothAccepted()) return

        state = State.AWAITING_RESPONSE
        startTimeout()
        Log.i(TAG, "Accepted locally, awaiting $deviceName")
    }

    /** Kept for the UI call site that initiates rather than answers. */
    fun requestPair() = acceptPair()

    fun handlePairPacket(packet: BridgePacket) {
        val pairValue = packet.body["pair"]?.jsonPrimitive?.boolean
        if (pairValue == null) {
            Log.w(TAG, "Invalid pair packet: missing 'pair' field")
            return
        }

        if (pairValue) {
            // "request", "accept", or null for peers that predate the field.
            handlePeerAffirmation(packet.body["kind"]?.jsonPrimitive?.contentOrNull)
        } else {
            handlePairRejectOrUnpair()
        }
    }

    fun rejectPair() {
        localDecision = Decision.DECLINED
        sendPacket(BridgePacket.pairReject())
        cancelTimeout()
        if (state != State.PAIRED) {
            state = State.UNPAIRED
        }
        Log.i(TAG, "Rejected pair request")
    }

    fun unpair() {
        val deviceId = pairedDeviceId ?: return
        sendPacket(BridgePacket.pairReject())
        trustStore().unpin(deviceId)
        state = State.UNPAIRED
        pairedDeviceId = null
        pairedDeviceName = null
        resetDecisions()
        Log.i(TAG, "Unpaired device $deviceId")
    }

    fun reset() {
        cancelTimeout()
        peerDeviceId = null
        peerDeviceName = null
        peerFingerprint = null
        resetDecisions()
        if (state != State.PAIRED) {
            state = State.UNPAIRED
        }
    }

    private fun resetDecisions() {
        localDecision = Decision.PENDING
        remoteDecision = Decision.PENDING
    }

    /** `kind` is "request", "accept", or null — either way `pair: true` means the peer's user accepted. */
    private fun handlePeerAffirmation(kind: String?) {
        val deviceId = peerDeviceId
        val deviceName = peerDeviceName
        val fingerprint = peerFingerprint
        if (deviceId == null || deviceName == null || fingerprint == null) {
            Log.w(TAG, "Pair packet before peer identity — ignoring")
            return
        }

        remoteDecision = Decision.ACCEPTED
        cancelTimeout()

        if (pinIfBothAccepted()) return

        if (kind == "request" && state == State.AWAITING_RESPONSE &&
            !yieldsOnCrossedRequest(localDeviceId, deviceId)
        ) {
            Log.i(TAG, "Crossed pair request from $deviceName — we stay the initiator")
        }

        Log.i(TAG, "$deviceName accepted — awaiting this device's user")
        presentPairConfirmation(deviceName, fingerprint)
    }

    /** @return true when both sides have accepted and the peer is now pinned. */
    private fun pinIfBothAccepted(): Boolean {
        if (localDecision != Decision.ACCEPTED || remoteDecision != Decision.ACCEPTED) return false
        val deviceId = peerDeviceId ?: return false
        val deviceName = peerDeviceName ?: return false
        val fingerprint = peerFingerprint ?: return false

        trustStore().pin(deviceId, fingerprint)
        state = State.PAIRED
        pairedDeviceId = deviceId
        pairedDeviceName = deviceName
        cancelTimeout()
        Log.i(TAG, "Both sides accepted — pinned $deviceName")
        onPairCompleted?.invoke()
        return true
    }

    private fun presentPairConfirmation(deviceName: String, fingerprint: String) {
        if (localDecision != Decision.PENDING) return
        val code = verificationCode ?: run {
            // No code means no way for the user to tell this peer from an impostor,
            // so there is nothing safe to show and nothing to pin.
            Log.e(TAG, "Cannot derive a verification code without a local fingerprint")
            return
        }
        onPairRequest?.invoke(deviceName, fingerprint, code)
    }

    private fun handlePairRejectOrUnpair() {
        remoteDecision = Decision.DECLINED
        cancelTimeout()

        when (state) {
            State.PAIRED -> {
                val deviceId = pairedDeviceId ?: return
                trustStore().unpin(deviceId)
                state = State.UNPAIRED
                pairedDeviceId = null
                pairedDeviceName = null
                resetDecisions()
                Log.i(TAG, "Unpaired by peer")
            }
            State.AWAITING_RESPONSE, State.UNPAIRED -> {
                // Nothing was pinned, and a decline on either side leaves both trust
                // stores exactly as they were.
                state = State.UNPAIRED
                resetDecisions()
                Log.i(TAG, "Peer declined pairing — nothing pinned")
            }
        }
    }

    /**
     * Bounds the *peer's* decision only. A confirmation this device's user has not
     * answered never expires: a prompt that vanishes on its own is a dead end.
     */
    private fun startTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            // Local expiry only. Sending a reject here would make a peer that already
            // pinned us drop that pin, which is how the link ended up half-paired.
            Log.i(TAG, "Peer did not answer in time — giving up locally")
            if (state == State.AWAITING_RESPONSE) {
                state = State.UNPAIRED
                resetDecisions()
            }
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }
}
