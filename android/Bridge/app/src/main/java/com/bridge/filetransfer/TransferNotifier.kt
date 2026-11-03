package com.bridge.filetransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Surfaces outbound file-transfer progress and result on the phone as
 * notifications: a live "Sending … X%" bar that resolves to "Sent ✓" or
 * "Failed ✗ (reason)". Each transfer gets its own notification id so a failure
 * stays visible alongside later successes.
 */
class TransferNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "bridge_transfers"
        private const val BASE_ID = 1000
        const val ACTION_ANSWER_OFFER = "com.bridge.FILE_OFFER_ANSWER"
        const val EXTRA_OFFER_ID = "com.bridge.EXTRA_OFFER_ID"
        const val EXTRA_ACCEPTED = "com.bridge.EXTRA_ACCEPTED"
        const val EXTRA_NOTIF_ID = "com.bridge.EXTRA_NOTIF_ID"
    }

    private val nm =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var seq = 0

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Transfers",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    /**
     * Announce an incoming file and wait for an answer. Nothing is fetched or written until the
     * user accepts, so the offer carries the only two answers that count.
     */
    fun offerReceive(name: String, total: Long, offerId: String): Int {
        val id = BASE_ID + seq++
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Incoming file")
            .setContentText("$name — ${formatBytes(total)}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(answerAction("Accept", offerId, id, accepted = true))
            .addAction(answerAction("Decline", offerId, id, accepted = false))
            .build()
        nm.notify(id, notification)
        return id
    }

    private fun answerAction(
        title: String, offerId: String, notifId: Int, accepted: Boolean
    ): Notification.Action {
        val intent = Intent(ACTION_ANSWER_OFFER)
            .setPackage(context.packageName)
            .putExtra(EXTRA_OFFER_ID, offerId)
            .putExtra(EXTRA_ACCEPTED, accepted)
            .putExtra(EXTRA_NOTIF_ID, notifId)
        val pending = PendingIntent.getBroadcast(
            context,
            // Distinct request codes, or the two actions share one PendingIntent.
            notifId * 2 + if (accepted) 1 else 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, title, pending).build()
    }

    fun cancel(id: Int) = nm.cancel(id)

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes bytes"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format("%.1f %s", value, units[unit])
    }

    /** Begin a transfer; returns its notification id. */
    fun start(name: String, total: Long): Int {
        val id = BASE_ID + (seq++)
        progressNotification(id, name, "Sending to Mac…", 0)
        return id
    }

    fun progress(id: Int, name: String, sent: Long, total: Long) {
        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
        progressNotification(id, name, "Sending to Mac… $pct%", pct)
    }

    fun complete(id: Int, name: String, total: Long) {
        val sizeMb = String.format("%.1f", total / (1024.0 * 1024.0))
        nm.notify(id, terminal(name, "Sent to Mac ✓  ($sizeMb MB)",
            android.R.drawable.stat_sys_upload_done))
    }

    fun failure(id: Int, name: String, reason: String) {
        nm.notify(id, terminal(name, "Failed: $reason",
            android.R.drawable.stat_notify_error))
    }

    // Receive direction (Mac -> phone).

    fun startReceive(name: String, total: Long): Int {
        val id = BASE_ID + (seq++)
        receiveProgressNotification(id, name, "Receiving from Mac…", 0)
        return id
    }

    fun progressReceive(id: Int, name: String, received: Long, total: Long) {
        val pct = if (total > 0) (received * 100 / total).toInt() else 0
        receiveProgressNotification(id, name, "Receiving from Mac… $pct%", pct)
    }

    fun completeReceive(id: Int, name: String, total: Long) {
        val sizeMb = String.format("%.1f", total / (1024.0 * 1024.0))
        nm.notify(id, terminal(name, "Saved from Mac ✓  ($sizeMb MB)",
            android.R.drawable.stat_sys_download_done))
    }

    private fun receiveProgressNotification(id: Int, title: String, text: String, pct: Int) {
        val n = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(id, n)
    }

    private fun progressNotification(id: Int, title: String, text: String, pct: Int) {
        val n = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(id, n)
    }

    private fun terminal(title: String, text: String, icon: Int): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
}
