package com.blushdesk.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.blushdesk.app.TestData
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.repository.OfflineInventoryRepository
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.PhotoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The phone side of the reminders: due reminders become real notifications, once, and the
 * background job that drives them is scheduled with WorkManager and runs.
 */
@RunWith(AndroidJUnit4::class)
class InventoryNotifierTest {

    private class NoPhotos : PhotoStore {
        override suspend fun importPhoto(source: Uri): String = error("unused")
        override fun delete(imageUri: String?) = Unit
    }

    private class TestClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var clock: TestClock
    private lateinit var inventory: OfflineInventoryRepository
    private lateinit var notifier: InventoryNotifier

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        db = TestData.inMemoryDatabase(context)
        clock = TestClock(Instant.parse("2026-10-20T01:00:00Z"))
        inventory = OfflineInventoryRepository(db, NoPhotos(), clock, ZoneId.of("Asia/Manila"))
        notifier = InventoryNotifier(context, inventory)
        NotificationManagerCompat.from(context).cancelAll()
    }

    @After
    fun tearDown() {
        NotificationManagerCompat.from(context).cancelAll()
        db.close()
    }

    private fun posted() = context.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.tag == "inventory" }

    @Test
    fun a_due_reminder_becomes_one_phone_notification() = runBlocking {
        val id = inventory.saveProduct(Product(name = "USB Keyboard", sellingPrice = Money.of("850"), minimumStock = 2), initialStock = 9)
        inventory.setReminder(id, ReminderInterval.EVERY_3_DAYS)

        notifier.sync()
        assertTrue("nothing is due yet", posted().isEmpty())

        clock.now = clock.now.plus(Duration.ofDays(3))
        notifier.sync()
        notifier.sync() // a second run, as if the job ran twice

        val shown = posted()
        assertEquals(1, shown.size)
        assertEquals("May stock pa: USB Keyboard", shown.single().notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(NotificationChannelIds.REMINDERS, shown.single().notification.channelId)
        assertEquals(1, inventory.notifications.first().size)
        assertTrue(inventory.unpostedNotifications().isEmpty())
    }

    @Test
    fun a_low_stock_alert_goes_to_its_own_channel() = runBlocking {
        val id = inventory.saveProduct(Product(name = "Wireless Mouse", sellingPrice = Money.of("450"), minimumStock = 5), initialStock = 6)
        inventory.adjustStock(id, -2, com.blushdesk.app.domain.model.AdjustmentReason.DAMAGED, "")

        notifier.sync()

        val shown = posted().single()
        assertEquals(NotificationChannelIds.LOW_STOCK, shown.notification.channelId)
        assertEquals("Low stock: Wireless Mouse", shown.notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun the_reminder_job_is_scheduled_with_workmanager() {
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReminderWorker.UNIQUE_NAME).get()
        assertEquals(1, work.size)
        assertTrue(work.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))
    }

    @Test
    fun the_reminder_job_runs_successfully() = runBlocking {
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    /** The channel ids, read from the notifier so the test follows any rename. */
    private object NotificationChannelIds {
        const val REMINDERS = InventoryNotifier.CHANNEL_REMINDERS
        const val LOW_STOCK = InventoryNotifier.CHANNEL_LOW_STOCK
    }
}
