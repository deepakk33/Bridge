package com.bridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.bridge.protocol.BridgePacket
import com.bridge.protocol.PingHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

class L2capServer(
    adapter: BluetoothAdapter,
    private val onConnectionChanged: (connected: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "L2capServer"
    }

    private val serverSocket: BluetoothServerSocket =
        adapter.listenUsingInsecureL2capChannel()

    val psm: Int = serverSocket.psm

    private var activeSocket: BluetoothSocket? = null

    suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        Log.i(TAG, "L2CAP accept loop started on PSM=$psm")
        while (isActive) {
            try {
                val socket = serverSocket.accept()
                Log.i(TAG, "L2CAP connection accepted")
                activeSocket = socket
                onConnectionChanged(true)
                handleConnection(socket)
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Accept failed", e)
                }
                break
            } finally {
                activeSocket = null
                onConnectionChanged(false)
            }
        }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val outputStream = socket.outputStream

            var bytesReceived = 0L
            val startTime = System.currentTimeMillis()

            var line = reader.readLine()
            while (line != null) {
                bytesReceived += line.toByteArray(Charsets.UTF_8).size + 1
                try {
                    val packet = BridgePacket.deserialize(line)
                    Log.d(TAG, "Received packet: type=${packet.type}")
                    handlePacket(packet, outputStream)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse packet: ${e.message}")
                }
                line = reader.readLine()
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Connection closed. Received $bytesReceived bytes in ${elapsed}ms")
        } catch (e: IOException) {
            Log.e(TAG, "Connection error", e)
        } finally {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing socket", e)
            }
        }
    }

    private fun handlePacket(packet: BridgePacket, outputStream: OutputStream) {
        when (packet.type) {
            "bridge.ping" -> {
                val response = PingHandler.handle(packet)
                send(response, outputStream)
            }
            else -> {
                Log.w(TAG, "Unknown packet type: ${packet.type}")
            }
        }
    }

    private fun send(packet: BridgePacket, outputStream: OutputStream) {
        try {
            val bytes = packet.serialize()
            outputStream.write(bytes)
            outputStream.flush()
            Log.d(TAG, "Sent packet: type=${packet.type}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send packet", e)
        }
    }

    fun close() {
        try {
            activeSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing active socket", e)
        }
        try {
            serverSocket.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing server socket", e)
        }
    }
}
