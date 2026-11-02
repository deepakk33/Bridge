package com.bridge.filetransfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Out-of-band TCP payload server (KDE Connect payload mechanism).
 *
 * Binds an ephemeral port, streams the payload to the first connection that
 * arrives, then tears down. The source is opened lazily and streamed in chunks,
 * so a large file never has to sit fully in memory. Used by Tier 2
 * (RFCOMM-equivalent) and Tier 3 (Wi-Fi LAN); keeps the Tier 1 BLE control
 * channel free during transfer.
 *
 * The connection is mutual TLS against the peer's pinned fingerprint ([PayloadTls]), so
 * the payload never crosses the network in the clear and a peer that cannot prove it is
 * the pinned device gets no bytes at all.
 */
class PayloadServer private constructor(
    private val total: Long,
    private val openSource: () -> InputStream,
    private val timeoutMs: Int,
    private val sslContext: SSLContext
) {
    /** In-memory payload (used by tests and tiny transfers). */
    constructor(payload: ByteArray, sslContext: SSLContext, timeoutMs: Int = 30_000) :
        this(payload.size.toLong(), { ByteArrayInputStream(payload) }, timeoutMs, sslContext)

    /** File-backed payload, streamed from disk without loading it into memory. */
    constructor(file: File, sslContext: SSLContext, timeoutMs: Int = 30_000) :
        this(file.length(), { file.inputStream() }, timeoutMs, sslContext)

    companion object {
        private const val TAG = "PayloadServer"
        private const val CHUNK = 64 * 1024
    }

    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    /** Bind to an OS-assigned ephemeral port. Returns the port. Call before advertising. */
    fun bind(): Int {
        val socket = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        // The peer must present its certificate too: pinning one direction would leave
        // the sender serving a file to anyone who connects.
        socket.needClientAuth = true
        socket.soTimeout = timeoutMs
        serverSocket = socket
        return socket.localPort
    }

    /**
     * Serve the payload to the first peer that completes the pinned handshake, then close.
     * Returns bytes served. Throws [java.net.SocketTimeoutException] if none arrives in time.
     *
     * A connection that fails the handshake is discarded and the next one is accepted: on a
     * shared network a stranger reaching the port must not cost the real peer its transfer.
     */
    suspend fun serve(onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): Long =
        withContext(Dispatchers.IO) {
            val socket = serverSocket ?: throw IllegalStateException("serve() called before bind()")
            try {
                acceptVerified(socket as SSLServerSocket).use { client ->
                    openSource().use { input ->
                        client.getOutputStream().use { out ->
                            val buffer = ByteArray(CHUNK)
                            var sent = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                sent += n
                                onProgress(sent, total)
                            }
                            out.flush()
                        }
                    }
                }
                total
            } finally {
                close()
            }
        }

    /** Accept until one peer completes the handshake, or the deadline passes. */
    private fun acceptVerified(socket: SSLServerSocket): SSLSocket {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw java.net.SocketTimeoutException("no verified peer connected")
            socket.soTimeout = remaining.toInt()
            val client = socket.accept() as SSLSocket
            client.soTimeout = timeoutMs
            try {
                client.startHandshake()
                return client
            } catch (e: IOException) {
                // Not the peer we are waiting for; keep the port open for the one that is.
                Log.w(TAG, "Discarding a payload connection that failed the handshake: ${e.message}")
                try { client.close() } catch (_: IOException) {}
            }
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
