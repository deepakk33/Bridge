package com.bridge.app

/**
 * The three mutually-exclusive states the top-bar connect action and the
 * status card bind to (iter-7). Derived purely from trust + connection facts
 * so it can be unit-tested without Android.
 */
enum class LinkState {
    /** No trusted Mac in the trust store — offer to pair. */
    UNPAIRED,

    /** A Mac is trusted but the BLE link is currently down — offer to reconnect. */
    DISCONNECTED,

    /** The BLE link is up — status only. */
    CONNECTED;

    companion object {
        /**
         * Resolve link state from the two facts that fully determine it.
         * Connection wins: a live link is CONNECTED regardless of trust store
         * contents (a peer may re-pair after losing its trust store).
         */
        fun resolve(hasTrustedMac: Boolean, isConnected: Boolean): LinkState = when {
            isConnected -> CONNECTED
            hasTrustedMac -> DISCONNECTED
            else -> UNPAIRED
        }

        /** Label shown on the single adaptive connect action for each state. */
        fun actionLabel(state: LinkState): String = when (state) {
            UNPAIRED -> "Pair Mac"
            DISCONNECTED -> "Reconnect"
            CONNECTED -> "Connected"
        }
    }
}
