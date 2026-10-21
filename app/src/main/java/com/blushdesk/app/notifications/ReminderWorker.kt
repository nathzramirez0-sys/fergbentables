package com.blushdesk.app.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blushdesk.app.FergBentablesApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The background job behind the "May stock pa" reminders. Android's WorkManager runs it about
 * every [INTERVAL_HOURS] hours whether or not the app is open, and keeps the schedule across
 * restarts of the app and the device. Each run records the reminders that have come due and
 * posts them ([InventoryNotifier.sync]); running twice for the same moment records nothing new.
 *
 * Reminders are therefore on time to within a few hours, which suits intervals of days. A failed
 * run is retried with WorkManager's back-off.
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        (applicationContext as FergBentablesApp).container.notifier.sync()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Inventory reminder run failed; it will be retried", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "ReminderWorker"
        const val UNIQUE_NAME = "inventory-reminders"
        const val INTERVAL_HOURS = 6L

        /** Schedules the job once; KEEP leaves an existing schedule alone on every later app start. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build(),
            )
        }
    }
}
