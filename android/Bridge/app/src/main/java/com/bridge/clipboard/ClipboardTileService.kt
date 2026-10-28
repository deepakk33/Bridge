package com.bridge.clipboard

import android.service.quicksettings.TileService
import android.util.Log
import com.bridge.app.BridgeService

class ClipboardTileService : TileService() {

    companion object {
        private const val TAG = "ClipboardTileService"
    }

    override fun onClick() {
        super.onClick()
        // The tile is a focused context, so it can read the clipboard without the ADB grant.
        val bridge = BridgeService.clipboardBridge
        if (bridge == null) {
            Log.w(TAG, "Bridge service not running")
            return
        }
        bridge.sendClipboardFrom(this)
    }
}
