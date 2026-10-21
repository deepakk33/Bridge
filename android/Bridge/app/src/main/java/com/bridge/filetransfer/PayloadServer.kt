package com.bridge.filetransfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket

/**
 * Out-of-band TCP payload server (KDE Connect payload mechanism).
 *
 * Binds an ephemeral port, serves the file bytes to the first connection that
 * arrives, then tears down. Used by Tier 2 (RFCOMM-equivalent) and Tier 3
 * (Wi-Fi LAN). Keeps the Tier 1 BLE control channel free during transfer.
 */
class PayloadServer(
    private val payload: ByteArray,
    private val timeoutMs: Int = 30_000
) {
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    /** Bind to an OS-assigned ephemeral port. Returns the port. Call before advertising. */
    fun bind(): Int {
        val socket = ServerSocket(0)
        socket.soTimeout = timeoutMs
        serverSocket = socket
        return socket.localPort
    }

    /**
     * Accept one connection, serve the payload, close. Returns bytes served.
     * Throws [java.net.SocketTimeoutException] if no connection arrives in time.
     */
    suspend fun serve(): Int = withContext(Dispatchers.IO) {
        val socket = serverSocket ?: throw IllegalStateException("serve() called before bind()")
        try {
            socket.accept().use { client ->
                client.soTimeout = timeoutMs
                client.getOutputStream().use { out ->
                    out.write(payload)
                    out.flush()
                }
            }
            payload.size
        } finally {
            close()
        }
    }

    fun close() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
    }
}
