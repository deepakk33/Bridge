package com.bridge.clipboard

import android.content.ClipboardManager
import android.service.quicksettings.TileService
import android.util.Log
import com.bridge.protocol.BridgePacket

class ClipboardTileService : TileService() {

    companion object {
        private const val TAG = "ClipboardTileService"
        var packetSender: ((BridgePacket) -> Unit)? = null
    }

    override fun onClick() {
        super.onClick()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read clipboard: ${e.message}")
            null
        }

        if (text.isNullOrEmpty()) {
            Log.d(TAG, "No clipboard text to send")
            return
        }

        val packet = BridgePacket.clipboard(text)
        val sender = packetSender
        if (sender != null) {
            sender(packet)
            Log.i(TAG, "Sent clipboard via quick-settings tile")
        } else {
            Log.w(TAG, "No packet sender configured")
        }
    }
}
