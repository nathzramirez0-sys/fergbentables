package com.blushdesk.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.blushdesk.app.MainActivity
import com.blushdesk.app.R
import com.blushdesk.app.data.local.database.InventoryNotification
import com.blushdesk.app.domain.model.NotificationKind
import com.blushdesk.app.domain.repository.InventoryRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns inventory notifications into phone notifications.
 *
 * [sync] is the one entry point, called by the background job, when the app starts and after
 * every stock change: it records the reminders that are due, posts every notification not yet
 * posted, and marks them posted. Each is posted under its own database id, so posting the same
 * one twice (a crash between posting and marking) replaces it rather than showing it twice.
 *
 * Without the notification permission (Android 13+) nothing is posted, and notifications are
 * still marked posted: they stay in the app's history, and allowing notifications later does not
 * release a backlog of old ones.
 */
class InventoryNotifier(private val context: Context, private val repository: InventoryRepository) {

    private val lock = Mutex()

    suspend fun sync() = lock.withLock {
        repository.recordDueReminders()
        val pending = repository.unpostedNotifications()
        if (pending.isEmpty()) return@withLock
        if (canPost()) {
            ensureChannels()
            pending.forEach(::post)
        }
        repository.markPosted(pending.map { it.id })
    }

    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun post(notification: InventoryNotification) {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_INVENTORY_NOTIFICATIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val built = NotificationCompat.Builder(context, channelOf(notification.kind))
            .setSmallIcon(R.drawable.ic_stat_inventory)
            .setColor(ContextCompat.getColor(context, R.color.blush_vibrant_rose))
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(notification.createdAt.toEpochMilli())
            .setShowWhen(true)
            .setCategory(if (notification.kind == NotificationKind.LOW_STOCK) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, notification.id.toInt(), built)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the call: the history in the app remains.
            Log.w(TAG, "Notification not posted", e)
        }
    }

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Stock reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "\"May stock pa\": products that still have stock, on the schedule you choose for each."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LOW_STOCK, "Low-stock alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a product falls to its minimum stock or runs out."
            },
        )
    }

    private fun channelOf(kind: NotificationKind) = when (kind) {
        NotificationKind.REMAINING_STOCK -> CHANNEL_REMINDERS
        NotificationKind.LOW_STOCK -> CHANNEL_LOW_STOCK
    }

    companion object {
        private const val TAG = "inventory"
        private const val REQUEST_OPEN = 7
        const val CHANNEL_REMINDERS = "stock_reminders"
        const val CHANNEL_LOW_STOCK = "low_stock"
    }
}
