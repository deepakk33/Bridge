package com.bridge.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bridge.permissions.PermissionStatusChecker
import com.bridge.protocol.BridgePacket
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** What the most recent phone-to-Mac send did with the clip. */
data class SendOutcome(val originalBytes: Int, val truncated: Boolean)

class ClipboardBridge(
    private val context: Context,
    private val sendPacket: (BridgePacket) -> Unit
) {
    companion object {
        private const val TAG = "ClipboardBridge"
        private const val POLL_INTERVAL_MS = 500L
    }

    /**
     * The app op `android:read_clipboard` is ALLOWED for every app and says nothing about
     * background access, so it cannot gate the poll. The grant that matters is
     * READ_CLIPBOARD_IN_BACKGROUND, which is role-managed on Android 16 and cannot be held
     * by a sideloaded build — so on this hardware this is always false and no poll runs.
     */
    val hasBackgroundClipboardAccess: Boolean
        get() = context.checkSelfPermission(
            PermissionStatusChecker.READ_CLIPBOARD_IN_BACKGROUND
        ) == PackageManager.PERMISSION_GRANTED

    var lastClipboardTimestamp: Long = 0L
        private set

    /** Null once a clip has been sent whole; set while the last send had to be cut. */
    var lastSend: SendOutcome? = null
        private set

    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var isConnected = false
        private set

    /** Whether a background watcher is actually running — not merely whether the grant exists. */
    val isWatching: Boolean
        get() = polling
    var activityContext: Context? = null

    private var isRemoteWrite = false
    private var pendingRemoteContent: String? = null
    private var lastContent: String? = null
    private var polling = false
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollClipboard()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (!hasBackgroundClipboardAccess) {
            Log.i(TAG, "No background clipboard grant; poll not started (tile and in-app send still work)")
            return
        }
        if (!polling) {
            polling = true
            lastContent = getCurrentClipText()
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
            Log.i(TAG, "Clipboard polling started")
        }
    }

    fun stop() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun cappedPacket(text: String): BridgePacket {
        val capped = ClipboardCap.apply(text)
        lastSend = if (capped.truncated) SendOutcome(capped.originalBytes, true) else null
        return BridgePacket.clipboard(capped.text)
    }

    fun handleClipboardPacket(packet: BridgePacket) {
        val content = packet.body["content"]?.jsonPrimitive?.content ?: return

        val current = getCurrentClipText()
        if (current == content) return

        applyRemote(content)
        Log.i(TAG, "Clipboard updated from remote")
    }

    fun handleClipboardConnectPacket(packet: BridgePacket) {
        val content = packet.body["content"]?.jsonPrimitive?.content ?: return
        val remoteTimestamp = packet.body["timestamp"]?.jsonPrimitive?.long ?: return

        if (remoteTimestamp == 0L) return

        val diff = remoteTimestamp - lastClipboardTimestamp
        if (diff <= 100) return

        val current = getCurrentClipText()
        if (current == content) return

        applyRemote(content)
        lastClipboardTimestamp = remoteTimestamp
        Log.i(TAG, "Clipboard updated from reconnect (remote ts=$remoteTimestamp)")
    }

    /**
     * Write a clip received from the Mac. Android 16 denies clipboard writes from
     * a backgrounded service, so cache it as pending; [flushPendingRemote] re-applies
     * it when the app next has focus. When an Activity context is live the write
     * lands immediately and nothing stays pending.
     */
    private fun applyRemote(content: String) {
        isRemoteWrite = true
        pendingRemoteContent = content
        val cm = (activityContext ?: context).getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            cm.setPrimaryClip(ClipData.newPlainText("Bridge", content))
            lastContent = content
            if (activityContext != null) pendingRemoteContent = null
        } catch (e: Exception) {
            Log.d(TAG, "Remote clip write deferred: ${e.message}")
        }
    }

    /** Re-apply the last Mac clip that couldn't be written while backgrounded. Call on focus. */
    fun flushPendingRemote() {
        val content = pendingRemoteContent ?: return
        val ctx = activityContext ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        isRemoteWrite = true
        try {
            cm.setPrimaryClip(ClipData.newPlainText("Bridge", content))
            lastContent = content
            pendingRemoteContent = null
            Log.i(TAG, "Flushed pending remote clip on focus")
        } catch (e: Exception) {
            Log.d(TAG, "Pending flush failed: ${e.message}")
        }
    }

    fun makeConnectPacket(): BridgePacket {
        val capped = ClipboardCap.apply(getCurrentClipText() ?: "")
        lastSend = if (capped.truncated) SendOutcome(capped.originalBytes, true) else null
        return BridgePacket.clipboardConnect(capped.text, lastClipboardTimestamp)
    }

    fun onConnectionReady() {
        isConnected = true
        lastContent = null
        Log.i(TAG, "Connection ready, reset lastContent for fresh send")
    }

    fun onConnectionLost() {
        isConnected = false
        Log.i(TAG, "Connection lost")
    }

    fun sendCurrentClipboard() {
        val text = getCurrentClipText()
        if (text.isNullOrEmpty()) {
            Log.d(TAG, "No clipboard text to send")
            return
        }
        lastClipboardTimestamp = System.currentTimeMillis()
        sendPacket(cappedPacket(text))
        Log.i(TAG, "Sent current clipboard via manual trigger")
    }

    private fun pollClipboard() {
        if (isRemoteWrite) {
            isRemoteWrite = false
            return
        }

        val text = getCurrentClipText()
        if (text.isNullOrEmpty()) return
        if (text == lastContent) return
        if (!isConnected) return

        lastContent = text
        lastClipboardTimestamp = System.currentTimeMillis()

        sendPacket(cappedPacket(text))
        Log.i(TAG, "Clipboard change detected, sent to remote")
    }

    /** Send the clip read through [focusedContext] — an Activity or a tile, never the service. */
    fun sendClipboardFrom(focusedContext: Context) {
        if (!isConnected) {
            Log.i(TAG, "Not connected, skipping clipboard send")
            return
        }
        val cm = focusedContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read clipboard from activity: ${e.message}")
            null
        }
        if (text.isNullOrEmpty()) {
            Log.i(TAG, "No clipboard text from activity context")
            return
        }
        if (text == lastContent) {
            Log.i(TAG, "Clipboard same as last sent, skipping")
            return
        }
        lastContent = text
        lastClipboardTimestamp = System.currentTimeMillis()
        sendPacket(cappedPacket(text))
        Log.i(TAG, "Clipboard sent from focused context (${text.length} chars)")
    }

    private fun getCurrentClipText(): String? {
        val ctx = activityContext
        val cm = if (ctx != null) {
            ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        } else {
            clipboardManager
        }
        return try {
            cm.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read clipboard: ${e.message}")
            null
        }
    }
}
