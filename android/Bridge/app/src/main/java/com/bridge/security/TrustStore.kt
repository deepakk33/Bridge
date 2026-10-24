package com.bridge.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TrustStore private constructor(context: Context) {

    companion object {
        private const val TAG = "TrustStore"
        private const val PREFS_NAME = "bridge_trust_store"
        private const val KEY_PREFIX = "trusted_"

        @Volatile
        private var instance: TrustStore? = null

        fun init(context: Context): TrustStore {
            return instance ?: synchronized(this) {
                instance ?: TrustStore(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun getInstance(): TrustStore {
            return instance ?: throw IllegalStateException("TrustStore not initialized")
        }
    }

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // commit(), not apply(): a pin that is still in flight when the process dies
    // leaves the peer trusting us while we no longer trust it.
    fun pin(deviceId: String, fingerprint: String) {
        val written = prefs.edit().putString(KEY_PREFIX + deviceId, fingerprint).commit()
        Log.i(TAG, "Pinned fingerprint for device $deviceId (persisted=$written)")
    }

    fun unpin(deviceId: String) {
        prefs.edit().remove(KEY_PREFIX + deviceId).commit()
        Log.i(TAG, "Unpinned device $deviceId")
    }

    fun unpinAll() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Unpinned all devices")
    }

    fun isTrusted(deviceId: String, fingerprint: String): Boolean {
        return prefs.getString(KEY_PREFIX + deviceId, null) == fingerprint
    }

    fun hasPinnedDevice(deviceId: String): Boolean {
        return prefs.contains(KEY_PREFIX + deviceId)
    }

    fun pinnedFingerprint(deviceId: String): String? {
        return prefs.getString(KEY_PREFIX + deviceId, null)
    }

    val isEmpty: Boolean
        get() = prefs.all.isEmpty()
}
