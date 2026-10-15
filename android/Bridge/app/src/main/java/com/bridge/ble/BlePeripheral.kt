package com.bridge.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.util.Log
import com.bridge.security.CertificateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class BlePeripheral(
    private val context: Context,
    private val onConnectionStateChanged: (ConnectionState) -> Unit
) {

    companion object {
        private const val TAG = "BlePeripheral"
    }

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data object Advertising : ConnectionState()
        data class Connected(val deviceName: String?) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var l2capServer: L2capServer? = null
    private var l2capJob: Job? = null
    private var psm: Int = -1

    private val deviceId: String by lazy {
        val prefs = context.getSharedPreferences(
            BridgeConstants.PREFS_NAME, Context.MODE_PRIVATE
        )
        prefs.getString(BridgeConstants.DEVICE_ID_PREF_KEY, null)
            ?: UUID.randomUUID().toString().also { id ->
                prefs.edit().putString(BridgeConstants.DEVICE_ID_PREF_KEY, id).apply()
            }
    }

    private val identityJson: ByteArray by lazy {
        val certFingerprint = try {
            CertificateManager.getInstance().getFingerprint()
        } catch (_: Exception) {
            ""
        }
        val json = buildJsonObject {
            put("deviceId", deviceId)
            put("deviceName", Build.MODEL)
            put("deviceType", "phone")
            put("certificateFingerprint", certFingerprint)
        }
        json.toString().toByteArray(Charsets.UTF_8)
    }

    fun start() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            onConnectionStateChanged(ConnectionState.Error("Bluetooth not available or disabled"))
            return
        }

        try {
            CertificateManager.init(context).ensureCertificate()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize certificate", e)
        }

        startL2capServer()
        setupGattServer()
        startAdvertising()
    }

    fun stop() {
        stopAdvertising()
        l2capJob?.cancel()
        l2capJob = null
        l2capServer?.close()
        l2capServer = null
        gattServer?.close()
        gattServer = null
        try { scope.cancel() } catch (_: Exception) {}
        onConnectionStateChanged(ConnectionState.Idle)
    }

    private fun startL2capServer() {
        val adapter = bluetoothAdapter ?: return
        try {
            val server = L2capServer(adapter, deviceId) { connected ->
                if (connected) {
                    stopAdvertising()
                    onConnectionStateChanged(ConnectionState.Connected("L2CAP peer"))
                } else {
                    onConnectionStateChanged(ConnectionState.Advertising)
                    startAdvertising()
                }
            }
            l2capServer = server
            psm = server.psm
            Log.i(TAG, "L2CAP server started on PSM=$psm")

            l2capJob = scope.launch {
                server.acceptLoop()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting L2CAP server", e)
            onConnectionStateChanged(ConnectionState.Error("Bluetooth permission denied"))
        }
    }

    private fun setupGattServer() {
        val service = BluetoothGattService(
            BridgeConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val identityChar = BluetoothGattCharacteristic(
            BridgeConstants.IDENTITY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val psmChar = BluetoothGattCharacteristic(
            BridgeConstants.PSM_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(identityChar)
        service.addCharacteristic(psmChar)

        try {
            gattServer = bluetoothManager.openGattServer(context, gattCallback)?.also { server ->
                server.addService(service)
                Log.i(TAG, "GATT server started with Bridge service")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException opening GATT server", e)
            onConnectionStateChanged(ConnectionState.Error("Bluetooth permission denied"))
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            try {
                val value = when (characteristic.uuid) {
                    BridgeConstants.IDENTITY_CHARACTERISTIC_UUID -> identityJson
                    BridgeConstants.PSM_CHARACTERISTIC_UUID -> psm.toString()
                        .toByteArray(Charsets.UTF_8)
                    else -> {
                        gattServer?.sendResponse(
                            device, requestId,
                            android.bluetooth.BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            0, null
                        )
                        return
                    }
                }

                val responseBytes = if (offset >= value.size) {
                    ByteArray(0)
                } else {
                    value.copyOfRange(offset, value.size)
                }

                gattServer?.sendResponse(
                    device, requestId,
                    android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                    offset, responseBytes
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in GATT callback", e)
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            Log.d(TAG, "GATT connection state changed: device=${device.address}, state=$newState")
        }
    }

    private fun startAdvertising() {
        val adapter = bluetoothAdapter ?: return
        advertiser = adapter.bluetoothLeAdvertiser

        if (advertiser == null) {
            onConnectionStateChanged(ConnectionState.Error("BLE advertising not supported"))
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(BridgeConstants.SERVICE_PARCEL_UUID)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.i(TAG, "BLE advertising started")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting advertising", e)
            onConnectionStateChanged(ConnectionState.Error("Bluetooth permission denied"))
        }
    }

    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            Log.i(TAG, "BLE advertising stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping advertising", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising started successfully")
            onConnectionStateChanged(ConnectionState.Advertising)
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error code: $errorCode")
            onConnectionStateChanged(ConnectionState.Error("Advertising failed: code $errorCode"))
        }
    }
}
