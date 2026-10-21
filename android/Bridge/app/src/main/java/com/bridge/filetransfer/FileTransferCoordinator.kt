package com.bridge.filetransfer

import android.net.nsd.NsdManager
import android.util.Log
import com.bridge.protocol.BridgePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Orchestrates an outbound file transfer from Android to the Mac: selects the
 * tier, opens the payload channel, and emits the `bridge.share.request` control
 * packet via the injected [sendPacket]. Tier 3 (Wi-Fi LAN) advertises over mDNS;
 * Tier 2 (RFCOMM) serving runs on real hardware via [RfcommTransfer].
 */
class FileTransferCoordinator(
    private val nsdManager: NsdManager?,
    private val sendPacket: (BridgePacket) -> Unit
) {
    companion object {
        private const val TAG = "FileTransferCoordinator"
    }

    fun sendFile(
        scope: CoroutineScope,
        filename: String,
        bytes: ByteArray,
        sharedNetworkAvailable: Boolean
    ) {
        val size = bytes.size.toLong()
        val decision = TierRouter.decide(size, sharedNetworkAvailable)
        Log.i(TAG, "Sending $filename ($size bytes) via ${decision.tier}, fallback=${decision.isSlowFallback}")

        when (decision.tier) {
            TransferTier.WIFI_LAN -> {
                val server = PayloadServer(bytes)
                val port = server.bind()
                val nsd = nsdManager?.let { NsdTransfer(it) }
                nsd?.register(port)
                sendPacket(BridgePacket.shareRequest(filename, size, size, port))
                scope.launch(Dispatchers.IO) {
                    runCatching { server.serve() }
                        .onFailure { Log.w(TAG, "Wi-Fi transfer failed", it) }
                    nsd?.unregister()
                }
            }

            TransferTier.BLUETOOTH -> {
                // Tier 2: no TCP port; receiver connects over the RFCOMM UUID.
                sendPacket(BridgePacket.shareRequest(filename, size, size, null))
            }
        }
    }
}
