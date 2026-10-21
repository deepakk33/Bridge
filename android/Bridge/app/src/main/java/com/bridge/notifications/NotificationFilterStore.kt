package com.bridge.notifications

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped store for the notification forwarding filter.
 *
 * Holds an in-memory [StateFlow] of [FilterState] loaded from [FilterPersistence]
 * at construction. The notification hot path reads [state].value synchronously
 * (never blocks, never launches a coroutine); the settings UI observes the flow.
 * Writes update the flow first (optimistic) then persist fire-and-forget.
 *
 * NOTE: persists via SharedPreferences to match the existing project pattern
 * (TrustStore / EncryptedSharedPreferences), not Jetpack DataStore — see
 * design.md D2. SharedPreferences reads are synchronous after first load.
 */
class NotificationFilterStore(private val persistence: FilterPersistence) {

    private val _state = MutableStateFlow(persistence.load())
    val state: StateFlow<FilterState> = _state.asStateFlow()

    // --- synchronous accessors for the listener hot path ---

    fun isAllowed(pkg: String): Boolean = _state.value.isAllowed(pkg)

    fun isMuted(): Boolean = _state.value.masterMute

    fun shouldForward(pkg: String): Boolean = _state.value.shouldForward(pkg)

    // --- write operations (UI + listener) ---

    fun setAllowed(pkg: String, allow: Boolean) {
        val updated = _state.value.withAllowed(pkg, allow)
        _state.value = updated
        persistence.saveAllowed(updated.allowed)
    }

    fun setMasterMute(muted: Boolean) {
        val updated = _state.value.withMasterMute(muted)
        _state.value = updated
        persistence.saveMasterMute(updated.masterMute)
    }

    /** Records an app as seen so it appears in the toggle UI. No-op if already seen. */
    fun recordSeen(pkg: String) {
        val current = _state.value
        if (pkg in current.seen) return
        val updated = current.withSeen(pkg)
        _state.value = updated
        persistence.saveSeen(updated.seen)
    }

    companion object {
        private const val PREFS_NAME = "bridge_notification_filter"
        private const val KEY_ALLOWED = "allowed_packages"
        private const val KEY_MASTER_MUTE = "master_mute"
        private const val KEY_SEEN = "seen_packages"

        @Volatile
        private var instance: NotificationFilterStore? = null

        /** Idempotent singleton init; safe to call from both the service and the listener. */
        fun init(context: Context): NotificationFilterStore {
            return instance ?: synchronized(this) {
                instance ?: NotificationFilterStore(
                    SharedPreferencesPersistence(context.applicationContext)
                ).also { instance = it }
            }
        }

        fun getInstance(): NotificationFilterStore {
            return instance ?: throw IllegalStateException("NotificationFilterStore not initialized")
        }
    }

    private class SharedPreferencesPersistence(context: Context) : FilterPersistence {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun load(): FilterState = FilterState(
            allowed = prefs.getStringSet(KEY_ALLOWED, emptySet())?.toSet() ?: emptySet(),
            masterMute = prefs.getBoolean(KEY_MASTER_MUTE, false),
            seen = prefs.getStringSet(KEY_SEEN, emptySet())?.toSet() ?: emptySet()
        )

        // New Set instances on write: SharedPreferences must not be handed a set it
        // retains a reference to and later mutates.
        override fun saveAllowed(allowed: Set<String>) {
            prefs.edit().putStringSet(KEY_ALLOWED, HashSet(allowed)).apply()
        }

        override fun saveMasterMute(muted: Boolean) {
            prefs.edit().putBoolean(KEY_MASTER_MUTE, muted).apply()
        }

        override fun saveSeen(seen: Set<String>) {
            prefs.edit().putStringSet(KEY_SEEN, HashSet(seen)).apply()
        }
    }
}
