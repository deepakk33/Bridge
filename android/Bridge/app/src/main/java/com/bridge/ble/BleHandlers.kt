package com.bridge.ble

import com.bridge.protocol.BridgePacket

/**
 * The packet handlers the service supplies, held for the service's lifetime rather than the
 * peripheral's. One instance is wired once and passed to every [BlePeripheral] and [L2capServer]
 * built after it, so rebuilding either cannot drop a registration — there is no registration step
 * left to forget.
 *
 * [L2capServer.onLinkReady] and [L2capServer.onPeerVerified] stay with the server: the peripheral
 * wires those to itself at construction, so a rebuild re-wires them by definition.
 */
class BleHandlers {
    var onNotificationReply: ((requestReplyId: String, message: String) -> Unit)? = null
    var onNotificationRequest: ((cancel: String) -> Unit)? = null
    var onClipboardReceived: ((BridgePacket) -> Unit)? = null
    var onClipboardConnectReceived: ((BridgePacket) -> Unit)? = null
    var onShareRequest: ((BridgePacket) -> Unit)? = null
    var onPairCompleted: (() -> Unit)? = null
    var onPairConfirmationRequired: ((deviceName: String, code: String) -> Unit)? = null
    var onPairConfirmationCleared: (() -> Unit)? = null
    var onConnect: (() -> Unit)? = null
}
