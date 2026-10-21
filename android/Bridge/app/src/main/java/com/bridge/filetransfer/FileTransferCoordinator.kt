package com.bridge.filetransfer

import android.net.nsd.NsdManager
import android.util.Log
import com.bridge.protocol.BridgePacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Orchestrates an outbound file transfer from Android to the Mac: selects the
 * tier, opens the payload channel, and emits the `bridge.share.request` control
 * packet via the injected [sendPacket]. Tier 3 (Wi-Fi LAN) advertises over mDNS;
 * Tier 2 (RFCOMM) serving runs on real hardware via [RfcommTransfer].
 */
class FileTransferCoordinator(
    private val nsdManager: NsdManager?,
    private val notifier: TransferNotifier? = null,
    private val sendPacket: (BridgePacket) -> Unit
) {
    companion object {
        private const val TAG = "FileTransferCoordinator"
    }

    fun sendFile(
        scope: CoroutineScope,
        filename: String,
        file: File,
        sharedNetworkAvailable: Boolean
    ) {
        val size = file.length()
        val decision = TierRouter.decide(size, sharedNetworkAvailable)
        Log.i(TAG, "Sending $filename ($size bytes) via ${decision.tier}, fallback=${decision.isSlowFallback}")
        val notifId = notifier?.start(filename, size)

        when (decision.tier) {
            TransferTier.WIFI_LAN -> {
                val server = PayloadServer(file)
                val port = server.bind()
                val host = localWifiIp()
                val nsd = nsdManager?.let { NsdTransfer(it) }
                nsd?.register(port)
                Log.i(TAG, "Wi-Fi payload server on $host:$port")
                sendPacket(BridgePacket.shareRequest(filename, size, size, port, host))
                scope.launch(Dispatchers.IO) {
                    var lastPct = -1L
                    runCatching {
                        server.serve { sent, total ->
                            if (notifId != null) {
                                val pct = if (total > 0) sent * 100 / total else 0
                                if (pct >= lastPct + 5 || pct == 100L) {
                                    lastPct = pct
                                    notifier.progress(notifId, filename, sent, total)
                                }
                            }
                        }
                    }.onSuccess {
                        if (notifId != null) notifier.complete(notifId, filename, size)
                    }.onFailure {
                        Log.w(TAG, "Wi-Fi transfer failed", it)
                        if (notifId != null) {
                            notifier.failure(notifId, filename, it.message ?: "transfer failed")
                        }
                    }
                    nsd?.unregister()
                    file.delete()
                }
            }

            TransferTier.BLUETOOTH -> {
                // Tier 2: no TCP port; receiver connects over the RFCOMM UUID.
                // RFCOMM payload serving is not yet wired end-to-end (device work),
                // so report honestly rather than silently stalling.
                sendPacket(BridgePacket.shareRequest(filename, size, size, null))
                if (notifId != null) {
                    notifier.failure(notifId, filename, "Small-file Bluetooth path not available yet")
                }
                file.delete()
            }
        }
    }

    /** First non-loopback site-local IPv4 address (the Wi-Fi LAN address). */
    private fun localWifiIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "Could not determine local IP", e)
        null
    }
}
