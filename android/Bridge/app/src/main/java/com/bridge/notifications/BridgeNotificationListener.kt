package com.bridge.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bridge.protocol.BridgePacket
import java.util.UUID

class BridgeNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "BridgeNotifListener"
        private const val REPLY_CACHE_MAX = 200

        @Volatile
        var instance: BridgeNotificationListener? = null
            private set

        var packetSender: ((BridgePacket) -> Unit)? = null

        private val replyCache = object : LinkedHashMap<String, CachedReplyAction>(
            REPLY_CACHE_MAX + 1, 0.75f, true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedReplyAction>?
            ): Boolean = size > REPLY_CACHE_MAX
        }

        fun getCachedAction(requestReplyId: String): CachedReplyAction? {
            return synchronized(replyCache) { replyCache.remove(requestReplyId) }
        }

        fun isListenerEnabled(pm: PackageManager, packageName: String): Boolean {
            val cn = ComponentName(packageName, BridgeNotificationListener::class.java.name)
            val flat = android.provider.Settings.Secure.getString(
                null, "enabled_notification_listeners"
            )
            return flat?.contains(cn.flattenToString()) == true
        }
    }

    data class CachedReplyAction(
        val action: Notification.Action,
        val remoteInputs: Array<RemoteInput>,
        val notificationKey: String
    )

    private val filterStore by lazy { NotificationFilterStore.init(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationFilterStore.init(applicationContext)
        Log.i(TAG, "NotificationListener created")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "NotificationListener destroyed")
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return

        // iter-3 structural skips (ongoing / foreground-service / group-summary).
        if (NotificationGate.isStructuralSkip(
                isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                isForegroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            )
        ) return

        // iter-6: record the app as seen (so it can be toggled on later) BEFORE the
        // gate, then drop unless master mute is off and the app is explicitly allowed.
        filterStore.recordSeen(sbn.packageName)
        if (!filterStore.shouldForward(sbn.packageName)) return

        val extras = notification.extras ?: Bundle()
        val notifId = sbn.key
        val appName = try {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""

        val actions = notification.actions?.map { it.title.toString() } ?: emptyList()

        var requestReplyId: String? = null
        notification.actions?.forEach { action ->
            val remoteInputs = action.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty() && requestReplyId == null) {
                requestReplyId = UUID.randomUUID().toString()
                synchronized(replyCache) {
                    replyCache[requestReplyId!!] = CachedReplyAction(
                        action = action,
                        remoteInputs = remoteInputs,
                        notificationKey = notifId
                    )
                }
            }
        }

        val packet = BridgePacket.notification(
            id = notifId,
            appName = appName,
            title = title,
            text = text,
            actions = actions,
            requestReplyId = requestReplyId
        )

        packetSender?.invoke(packet) ?: Log.w(TAG, "No packet sender configured")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(replyCache) {
            val toRemove = replyCache.entries.filter { it.value.notificationKey == sbn.key }
            toRemove.forEach { replyCache.remove(it.key) }
        }
    }

    fun cancelNotificationByKey(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification: $key", e)
        }
    }
}
