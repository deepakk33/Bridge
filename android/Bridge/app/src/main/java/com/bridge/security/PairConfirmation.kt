package com.bridge.security

/**
 * The pending pair confirmation, shared between the foreground service that raises it
 * and the screen that answers it.
 *
 * A singleton for the same reason `BridgeNotificationListener.packetSender` is one: the
 * activity is started by the system, not constructed by us, so there is nowhere to pass
 * a reference through. Exactly one confirmation can be outstanding — the link carries one
 * peer at a time.
 */
object PairConfirmation {

    data class Pending(val deviceName: String, val code: String)

    @Volatile
    var pending: Pending? = null
        private set

    @Volatile
    private var onAnswer: ((accepted: Boolean) -> Unit)? = null

    /** Raised, and also cleared, by the service. */
    @Synchronized
    fun raise(deviceName: String, code: String, onAnswer: (accepted: Boolean) -> Unit) {
        pending = Pending(deviceName, code)
        this.onAnswer = onAnswer
    }

    /** Answering twice must not pin twice, so the handler is consumed. */
    @Synchronized
    fun answer(accepted: Boolean) {
        val handler = onAnswer ?: return
        onAnswer = null
        pending = null
        handler(accepted)
    }

    /** The link dropped or the peer withdrew — no answer is owed. */
    @Synchronized
    fun clear() {
        onAnswer = null
        pending = null
    }
}
