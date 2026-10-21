package com.bridge.filetransfer

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.bridge.protocol.BridgePacket
import com.bridge.protocol.payloadHost
import com.bridge.protocol.payloadPort
import com.bridge.protocol.payloadSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles an incoming `bridge.share.request` on the phone: connects to the Mac's
 * advertised host:port, streams the payload straight into a public Downloads
 * entry (MediaStore, no whole-file-in-RAM), and surfaces progress + result as a
 * notification. A partial download is deleted so no corrupt file is left behind.
 */
class FileReceiver(
    private val context: Context,
    private val notifier: TransferNotifier
) {
    companion object {
        private const val TAG = "FileReceiver"
    }

    fun receive(scope: CoroutineScope, packet: BridgePacket) {
        val size = packet.payloadSize
        val host = packet.payloadHost
        val port = packet.payloadPort
        val filename = packet.body["filename"]?.jsonPrimitive?.content ?: "BridgeFile"

        if (size <= 0 || host == null || port == null) {
            Log.w(TAG, "share.request missing payload info (size=$size host=$host port=$port)")
            return
        }
        Log.i(TAG, "Receiving $filename ($size bytes) from $host:$port")
        val notifId = notifier.startReceive(filename, size)

        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                notifier.failure(notifId, filename, "cannot create download file")
                return@launch
            }
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    val client = PayloadClient(host, port, size)
                    var lastPct = -1L
                    client.fetchTo(out) { received, total ->
                        val pct = if (total > 0) received * 100 / total else 0
                        if (pct >= lastPct + 5 || pct == 100L) {
                            lastPct = pct
                            notifier.progressReceive(notifId, filename, received, total)
                        }
                    }
                } ?: throw IllegalStateException("null output stream")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                notifier.completeReceive(notifId, filename, size)
                Log.i(TAG, "Saved $filename to Downloads")
            } catch (e: Exception) {
                Log.w(TAG, "Receive failed for $filename", e)
                runCatching { resolver.delete(uri, null, null) }
                notifier.failure(notifId, filename, e.message ?: "transfer failed")
            }
        }
    }
}
