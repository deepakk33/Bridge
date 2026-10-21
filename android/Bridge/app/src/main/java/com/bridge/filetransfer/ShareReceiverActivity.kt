package com.bridge.filetransfer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.bridge.app.BridgeService

/**
 * Receives files shared from any app ("Send to Bridge") and forwards them to the
 * foreground [BridgeService], which holds the BLE link and serves the payload.
 * Translucent, no UI — resolves the shared URI, hands it off, and finishes.
 */
class ShareReceiverActivity : Activity() {

    companion object {
        private const val TAG = "ShareReceiverActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = extractUri(intent)
        if (uri == null) {
            Toast.makeText(this, "Bridge: no file to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val forward = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_SEND_FILE
            putExtra(BridgeService.EXTRA_FILE_URI, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startService(forward)
        Log.i(TAG, "Forwarded $uri to BridgeService")
        Toast.makeText(this, "Sending to Mac…", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun extractUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
