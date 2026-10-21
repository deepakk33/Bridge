package com.bridge.ble

import android.os.ParcelUuid
import java.util.UUID

object BridgeConstants {

    val SERVICE_UUID: UUID =
        UUID.fromString("A76E1A00-B5D4-43C8-82BD-0E9F8A7B6C5D")

    val IDENTITY_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("A76E1A01-B5D4-43C8-82BD-0E9F8A7B6C5D")

    val PSM_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("A76E1A02-B5D4-43C8-82BD-0E9F8A7B6C5D")

    val SERVICE_PARCEL_UUID: ParcelUuid =
        ParcelUuid(SERVICE_UUID)

    /** Bluetooth Classic RFCOMM service UUID for Tier 2 file transfer. */
    val FILE_TRANSFER_UUID: UUID =
        UUID.fromString("A76E1A03-B5D4-43C8-82BD-0E9F8A7B6C5D")

    const val FILE_TRANSFER_SERVICE_NAME = "BridgeFileTransfer"

    /** mDNS service type for Tier 3 Wi-Fi LAN file transfer. */
    const val FILE_TRANSFER_SERVICE_TYPE = "_bridge._tcp"

    /** Legacy LOW channel — retained only for actionable alerts (e.g. the permission warning). */
    const val NOTIFICATION_CHANNEL_ID = "bridge_service_channel"

    /**
     * iter-7: IMPORTANCE_MIN channel for the content-less foreground-service
     * notification. A new id (not a lowered existing channel) because Android
     * ignores importance changes on an already-created channel.
     */
    const val NOTIFICATION_CHANNEL_ID_MIN = "bridge_service_min_channel"

    const val NOTIFICATION_ID = 1

    const val DEVICE_ID_PREF_KEY = "bridge_device_id"
    const val PREFS_NAME = "bridge_prefs"
}
