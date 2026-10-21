package com.bridge.filetransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

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
