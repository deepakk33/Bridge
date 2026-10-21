package com.bridge.filetransfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import com.bridge.ble.BridgeConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Tier 2 file transfer over Bluetooth Classic RFCOMM. No network required.
 *
 * Android acts as the RFCOMM server for files it sends to the Mac, and as an
 * RFCOMM client for files the Mac sends to it. The channel is opened per
 * transfer and torn down after, keeping the Tier 1 BLE control channel free.
 *
 * Requires BLUETOOTH_CONNECT. Device-only — exercised on real hardware.
 */
class RfcommTransfer(private val adapter: BluetoothAdapter) {

    companion object {
        private const val CHUNK = 8 * 1024
    }

    /** Server side: listen for the Mac to connect, serve [payload], close. */
    @SuppressLint("MissingPermission")
    suspend fun serve(payload: ByteArray): Int = withContext(Dispatchers.IO) {
        var server: BluetoothServerSocket? = null
        try {
            server = adapter.listenUsingRfcommWithServiceRecord(
                BridgeConstants.FILE_TRANSFER_SERVICE_NAME,
                BridgeConstants.FILE_TRANSFER_UUID
            )
            server.accept().use { socket ->
                socket.outputStream.use { out ->
                    out.write(payload)
                    out.flush()
                }
            }
            payload.size
        } finally {
            try {
                server?.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Client side: connect to the Mac's RFCOMM server, read exactly [expectedBytes]. */
    @SuppressLint("MissingPermission")
    suspend fun receive(device: BluetoothDevice, expectedBytes: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val socket = device.createRfcommSocketToServiceRecord(BridgeConstants.FILE_TRANSFER_UUID)
            socket.use {
                adapter.cancelDiscovery()
                it.connect()
                readExactly(it.inputStream, expectedBytes)
            }
        }

    private fun readExactly(input: InputStream, expected: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK)
        var total = 0L
        while (total < expected) {
            val toRead = minOf(chunk.size.toLong(), expected - total).toInt()
            val n = input.read(chunk, 0, toRead)
            if (n < 0) throw IOException("incomplete RFCOMM transfer: $total of $expected bytes")
            buffer.write(chunk, 0, n)
            total += n
        }
        return buffer.toByteArray()
    }
}
