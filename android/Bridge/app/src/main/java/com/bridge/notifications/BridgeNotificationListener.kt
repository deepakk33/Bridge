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

        private val replyCache = ReplyActionCache<CachedReplyAction>(REPLY_CACHE_MAX) {
            it.notificationKey
        }

        /**
         * Reads without consuming: a RemoteInput stays usable for as long as its
         * notification is live, so a conversation can be replied to more than once.
         * Eviction belongs to [onNotificationRemoved], keyed by notification.
         */
        fun getCachedAction(requestReplyId: String): CachedReplyAction? =
            replyCache.get(requestReplyId)

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
        if (!filterStore.shouldForward(sbn.packageName)) {
            Log.i(TAG, "Filtered out ${sbn.packageName} (muted=${filterStore.isMuted()}, allowed=${filterStore.isAllowed(sbn.packageName)})")
            return
        }
        Log.i(TAG, "Forwarding notification from ${sbn.packageName}")

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
                // An updated notification re-posts under the same key; without dropping
                // the previous entry each update would leave a dead one behind, and with
                // reads no longer consuming they would accumulate and evict other apps'
                // live reply actions out of the bounded cache.
                replyCache.evictNotification(notifId)
                replyCache.put(
                    requestReplyId!!,
                    CachedReplyAction(
                        action = action,
                        remoteInputs = remoteInputs,
                        notificationKey = notifId
                    )
                )
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
        if (sbn.packageName == packageName) return

        replyCache.evictNotification(sbn.key)

        // Tell the Mac to drop its copy. Without this, every notification read on the
        // phone leaves a stale one on the Mac. Dropped if the link is down, matching
        // the rule that a late notification is noise.
        //
        // Only for notifications that could have been forwarded, so the constant churn
        // of never-mirrored ones does not reach the channel: the same structural skips
        // as onNotificationPosted, then the allow-list.
        //
        // Deliberately not gated on master mute: pausing stops new notifications going
        // out, but the Mac should still be told about ones it already shows. An app
        // disallowed between posting and removal leaves one stale notification.
        val removed = sbn.notification
        if (removed != null && NotificationGate.isStructuralSkip(
                isOngoing = removed.flags and Notification.FLAG_ONGOING_EVENT != 0,
                isForegroundService = removed.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                isGroupSummary = removed.flags and Notification.FLAG_GROUP_SUMMARY != 0
            )
        ) return
        if (!filterStore.isAllowed(sbn.packageName)) return
        packetSender?.invoke(BridgePacket.notificationRequest(cancel = sbn.key))
    }

    fun cancelNotificationByKey(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification: $key", e)
        }
    }
}
