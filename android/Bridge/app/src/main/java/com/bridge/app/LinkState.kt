package com.bridge.app

/**
 * The honest, observable state of the link to the Mac (iter-7 / iter-8).
 *
 * "Connected" means a *verified* (trusted) secure link — not merely an open
 * Bluetooth socket. A socket to an untrusted peer delivers nothing, so it is
 * reported as [CONNECTING], never [CONNECTED]. Derived purely from four facts
 * so it can be unit-tested without Android.
 */
enum class LinkState {
    /** Bluetooth adapter is off — the link cannot exist. */
    BLUETOOTH_OFF,

    /** No trusted Mac in the trust store — offer to pair. */
    UNPAIRED,

    /** A Mac is trusted but no socket is open — offer to reconnect. */
    DISCONNECTED,

    /** A socket is open but the peer is not verified/trusted yet — nothing flows. */
    CONNECTING,

    /** A verified, trusted link is up — data flows. */
    CONNECTED;

    /** Whether this state needs the user's attention (anything but a working link). */
    val needsAttention: Boolean get() = this != CONNECTED

    companion object {
        /**
         * @param isBluetoothOn    adapter enabled
         * @param hasTrustedMac    trust store holds at least one pinned Mac
         * @param isSocketConnected an L2CAP socket is currently open
         * @param isVerified        the connected peer is trusted (mutual-TLS pin matches)
         */
        fun resolve(
            isBluetoothOn: Boolean,
            hasTrustedMac: Boolean,
            isSocketConnected: Boolean,
            isVerified: Boolean
        ): LinkState = when {
            !isBluetoothOn -> BLUETOOTH_OFF
            isSocketConnected && isVerified -> CONNECTED
            isSocketConnected -> CONNECTING
            hasTrustedMac -> DISCONNECTED
            else -> UNPAIRED
        }

        /** Label for the single adaptive top-bar connect action. */
        fun actionLabel(state: LinkState): String = when (state) {
            BLUETOOTH_OFF -> "Turn on Bluetooth"
            UNPAIRED -> "Pair Mac"
            DISCONNECTED -> "Reconnect"
            CONNECTING -> "Reconnect"
            CONNECTED -> "Connected"
        }

        /** Short status text for the state pill. */
        fun pillText(state: LinkState): String = when (state) {
            BLUETOOTH_OFF -> "Bluetooth off"
            UNPAIRED -> "Not paired"
            DISCONNECTED -> "Disconnected"
            CONNECTING -> "Connecting…"
            CONNECTED -> "Connected"
        }

        /** Pill color (hex), green only for a working link. */
        fun pillColorHex(state: LinkState): String = when (state) {
            BLUETOOTH_OFF -> "#F44336" // red
            UNPAIRED -> "#9E9E9E"      // grey
            DISCONNECTED -> "#FF9800"  // orange
            CONNECTING -> "#FFC107"    // amber
            CONNECTED -> "#4CAF50"     // green
        }

        /** Dialog title shown on open when the link is not working. */
        fun promptTitle(state: LinkState): String = when (state) {
            BLUETOOTH_OFF -> "Bluetooth is off"
            UNPAIRED -> "No Mac paired"
            DISCONNECTED -> "Mac not connected"
            CONNECTING -> "Connected, but not verified"
            CONNECTED -> ""
        }

        /** Dialog body shown on open when the link is not working. */
        fun promptBody(state: LinkState): String = when (state) {
            BLUETOOTH_OFF ->
                "Bridge needs Bluetooth to reach your Mac. Turn it on?"
            UNPAIRED ->
                "Pair with your Mac to start the link. Start pairing from the Mac; the phone will accept automatically."
            DISCONNECTED ->
                "Bridge can't reach your Mac right now. Try reconnecting."
            CONNECTING ->
                "Bridge reached your Mac but the secure pairing isn't verified, so nothing is delivered. Reconnect — and if it keeps failing, re-pair from the Mac."
            CONNECTED -> ""
        }
    }
}
