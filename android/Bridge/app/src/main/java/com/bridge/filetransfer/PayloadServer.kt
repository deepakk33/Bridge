package com.bridge.filetransfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket

/**
 * Out-of-band TCP payload server (KDE Connect payload mechanism).
 *
 * Binds an ephemeral port, streams the payload to the first connection that
 * arrives, then tears down. The source is opened lazily and streamed in chunks,
 * so a large file never has to sit fully in memory. Used by Tier 2
 * (RFCOMM-equivalent) and Tier 3 (Wi-Fi LAN); keeps the Tier 1 BLE control
 * channel free during transfer.
 */
class PayloadServer private constructor(
    private val total: Long,
    private val openSource: () -> InputStream,
    private val timeoutMs: Int
) {
    /** In-memory payload (used by tests and tiny transfers). */
    constructor(payload: ByteArray, timeoutMs: Int = 30_000) :
        this(payload.size.toLong(), { ByteArrayInputStream(payload) }, timeoutMs)

    /** File-backed payload, streamed from disk without loading it into memory. */
    constructor(file: File, timeoutMs: Int = 30_000) :
        this(file.length(), { file.inputStream() }, timeoutMs)

    companion object {
        private const val CHUNK = 64 * 1024
    }

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
     * Accept one connection, stream the payload, close. Returns bytes served.
     * Throws [java.net.SocketTimeoutException] if no connection arrives in time.
     */
    suspend fun serve(onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): Long =
        withContext(Dispatchers.IO) {
            val socket = serverSocket ?: throw IllegalStateException("serve() called before bind()")
            try {
                socket.accept().use { client ->
                    client.soTimeout = timeoutMs
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

    fun close() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
    }
}
