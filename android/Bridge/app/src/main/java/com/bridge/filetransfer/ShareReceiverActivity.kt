package com.bridge.filetransfer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import com.bridge.app.BridgeService
import java.io.File

/**
 * Receives files shared from any app ("Send to Bridge") and forwards them to the
 * foreground [BridgeService], which holds the BLE link and serves the payload.
 *
 * The incoming share intent grants this activity read access to the content URI.
 * An app cannot re-grant a URI it does not own, and a read grant does not
 * propagate to a non-exported service through an extra, so the activity copies the
 * shared bytes into app-private cache (which the service can read directly) and
 * passes that file path forward. Translucent, no UI.
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

        val cached = copyToCache(uri)
        if (cached == null) {
            Toast.makeText(this, "Bridge: could not read file", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val forward = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_SEND_FILE
            putExtra(BridgeService.EXTRA_FILE_PATH, cached.absolutePath)
            putExtra(BridgeService.EXTRA_FILE_NAME, displayName(uri) ?: cached.name)
        }
        startService(forward)
        Log.i(TAG, "Forwarded ${cached.name} (${cached.length()} bytes) to BridgeService")
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

    private fun copyToCache(uri: Uri): File? {
        return try {
            val outDir = File(cacheDir, "outgoing").apply { mkdirs() }
            val outFile = File(outDir, "share_${System.currentTimeMillis()}")
            val stream = contentResolver.openInputStream(uri) ?: return null
            stream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy shared file", e)
            null
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}
