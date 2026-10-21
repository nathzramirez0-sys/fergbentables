package com.blushdesk.app.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.TestData
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.repository.OfflineInventoryRepository
import com.blushdesk.app.data.repository.OfflineShowroomRepository
import com.blushdesk.app.domain.model.AdjustmentReason
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.NotificationKind
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.PhotoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The inventory's business rules on a real (in-memory) database: stock in, adjustments, the
 * automatic deduction when an order is saved, returns, archiving, low-stock alerts and the
 * "May stock pa" reminder schedule.
 */
@RunWith(AndroidJUnit4::class)
class InventoryRepositoryTest {

    private class NoPhotos : PhotoStore {
        override suspend fun importPhoto(source: Uri): String = error("unused")
        override fun delete(imageUri: String?) = Unit
    }

    /** A clock the test moves by hand. */
    private class TestClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private val zone = ZoneId.of("Asia/Manila")
    private lateinit var db: AppDatabase
    private lateinit var clock: TestClock
    private lateinit var inventory: OfflineInventoryRepository
    private lateinit var showroom: OfflineShowroomRepository
    private var buyerId = 0L

    @Before
    fun setUp() = runBlocking {
        db = TestData.inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        clock = TestClock(Instant.parse("2026-10-20T01:00:00Z"))
        inventory = OfflineInventoryRepository(db, NoPhotos(), clock, zone)
        showroom = OfflineShowroomRepository(db, NoPhotos(), clock)
        showroom.saveOperator(TestData.operator)
        buyerId = showroom.saveBuyer(TestData.buyer())
    }

    @After
    fun tearDown() = db.close()

    // ---- helpers ----------------------------------------------------------------------------

    private suspend fun addProduct(name: String = "USB Keyboard", stock: Int = 20, min: Int = 5, sku: String? = null, price: String = "850") =
        inventory.saveProduct(Product(name = name, sku = sku, sellingPrice = Money.of(price), minimumStock = min), stock)

    private suspend fun stock(productId: Long) = db.inventoryDao().getProduct(productId)!!.quantity
    private suspend fun product(productId: Long) = db.inventoryDao().getProduct(productId)!!
    private suspend fun movements(productId: Long) = db.inventoryDao().getMovementsForProduct(productId).map { it.movement }

    private fun line(productId: Long?, qty: Int, name: String = "USB Keyboard", price: String = "850") =
        OrderItem(productName = name, unitPrice = Money.of(price), quantity = qty, productId = productId)

    private suspend fun saveOrder(vararg lines: OrderItem, id: Long = 0) = showroom.saveOrder(
        Order(id = id, buyerId = buyerId, purchaseDateTime = clock.now, paymentMode = PaymentMode.CASH, paymentStatus = PaymentStatus.PAID),
        lines.toList(),
    )

    private suspend fun expectRefusal(containing: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected a refusal containing \"$containing\"")
        } catch (e: UserFacingException) {
            assertTrue("\"${e.message}\" should mention \"$containing\"", e.message!!.contains(containing, ignoreCase = true))
        }
    }

    // ---- Products ---------------------------------------------------------------------------

    @Test
    fun adding_a_product_records_its_initial_stock_as_the_first_movement() = runBlocking {
        val id = addProduct(stock = 20)

        assertEquals(20, stock(id))
        val first = movements(id).single()
        assertEquals(MovementType.INITIAL, first.type)
        assertEquals(0, first.previousQuantity)
        assertEquals(20, first.change)
        assertEquals(20, first.newQuantity)
        assertEquals("Lia Santos", first.performedBy)
    }

    @Test
    fun invalid_products_are_refused() = runBlocking {
        addProduct(sku = "kb-01")
        expectRefusal("already uses the code KB-01") { addProduct(name = "Other keyboard", sku = "KB-01") }
        expectRefusal("required") { addProduct(name = " ") }
        expectRefusal("greater than zero") { addProduct(name = "Free thing", price = "0") }
        expectRefusal("negative") { addProduct(name = "Odd", stock = -1) }
        assertEquals(1, inventory.products.first().size)
    }

    @Test
    fun editing_a_product_never_changes_its_stock() = runBlocking {
        val id = addProduct(stock = 20)
        inventory.saveProduct(product(id).copy(name = "USB Keyboard (black)", quantity = 999, sellingPrice = Money.of("899")))

        assertEquals(20, stock(id))
        assertEquals("USB Keyboard (black)", product(id).name)
        assertEquals(1, movements(id).size)
    }

    // ---- Stock in and adjustments -----------------------------------------------------------

    @Test
    fun stock_in_20_plus_10_makes_30_and_is_recorded() = runBlocking {
        val id = addProduct(stock = 20)
        inventory.stockIn(id, 10, clock.now, supplier = "Ace Supply", reference = "DR-1001", notes = "Second delivery")

        assertEquals(30, stock(id))
        val delivery = movements(id).last()
        assertEquals(MovementType.STOCK_IN, delivery.type)
        assertEquals(20, delivery.previousQuantity)
        assertEquals(10, delivery.change)
        assertEquals(30, delivery.newQuantity)
        assertEquals("Ace Supply", delivery.supplier)
        assertEquals("DR-1001", delivery.reference)
        assertEquals("Lia Santos", delivery.performedBy)
        assertEquals(clock.now, delivery.createdAt)
    }

    @Test
    fun adjusting_30_by_minus_5_makes_25_with_the_reason_and_the_user() = runBlocking {
        val id = addProduct(stock = 30)
        inventory.adjustStock(id, -5, AdjustmentReason.DAMAGED, "Dropped in the stockroom")

        assertEquals(25, stock(id))
        val adjustment = movements(id).last()
        assertEquals(MovementType.ADJUSTMENT, adjustment.type)
        assertEquals(30, adjustment.previousQuantity)
        assertEquals(-5, adjustment.change)
        assertEquals(25, adjustment.newQuantity)
        assertEquals("Damaged: Dropped in the stockroom", adjustment.reason)
        assertEquals("Lia Santos", adjustment.performedBy)
    }

    @Test
    fun stock_can_never_go_below_zero() = runBlocking {
        val id = addProduct(stock = 3)
        expectRefusal("Only 3") { inventory.adjustStock(id, -4, AdjustmentReason.LOST, "") }
        expectRefusal("removes stock") { inventory.adjustStock(id, 2, AdjustmentReason.DAMAGED, "") }
        assertEquals(3, stock(id))
        assertEquals(1, movements(id).size)
    }

    // ---- Sales ------------------------------------------------------------------------------

    @Test
    fun a_sale_of_3_takes_20_to_17_everywhere() = runBlocking {
        val id = addProduct(stock = 20)
        val orderId = saveOrder(line(id, 3))

        assertEquals(17, stock(id))
        val sale = movements(id).last()
        assertEquals(MovementType.SALE, sale.type)
        assertEquals(20, sale.previousQuantity)
        assertEquals(-3, sale.change)
        assertEquals(17, sale.newQuantity)
        assertEquals(orderId, sale.orderId)
        assertEquals("System", sale.performedBy)

        assertEquals(17, inventory.summary.first().totalUnits)
        assertEquals(3, inventory.summary.first().unitsSold)
        assertEquals(listOf(3), inventory.observeSales(id).first().map { it.quantity })
        assertEquals(StockStatus.IN_STOCK, inventory.observeProduct(id).first()!!.stockStatus)
    }

    @Test
    fun overselling_is_refused_and_nothing_is_saved() = runBlocking {
        val id = addProduct(stock = 5)
        expectRefusal("Only 5 pc of USB Keyboard are available") { saveOrder(line(id, 6)) }

        assertEquals(5, stock(id))
        assertEquals(0, showroom.observeOrders(buyerId).first().size)
        assertEquals(1, movements(id).size)

        val last = addProduct(name = "Brass Lamp", stock = 1)
        expectRefusal("Only 1 pc of Brass Lamp is available") { saveOrder(line(last, 2, name = "Brass Lamp")) }
    }

    @Test
    fun saving_the_same_order_again_does_not_deduct_twice() = runBlocking {
        val id = addProduct(stock = 20)
        val orderId = saveOrder(line(id, 3))
        assertEquals(17, stock(id))

        saveOrder(line(id, 3), id = orderId)
        saveOrder(line(id, 3), id = orderId)

        assertEquals(17, stock(id))
        assertEquals(2, movements(id).size) // the initial stock and the one sale
    }

    @Test
    fun editing_an_order_moves_only_the_difference() = runBlocking {
        val id = addProduct(stock = 20)
        val orderId = saveOrder(line(id, 3))

        saveOrder(line(id, 5), id = orderId)
        assertEquals(15, stock(id))
        saveOrder(line(id, 1), id = orderId)
        assertEquals(19, stock(id))

        val last = movements(id).last()
        assertEquals(MovementType.SALE_RETURN, last.type)
        assertEquals(4, last.change)
        // Swapping the line for a custom item gives everything back.
        saveOrder(line(null, 1, name = "Custom shelf"), id = orderId)
        assertEquals(20, stock(id))
    }

    @Test
    fun a_failed_sale_changes_no_stock_at_all() = runBlocking {
        val plenty = addProduct(name = "USB Keyboard", stock = 20)
        val scarce = addProduct(name = "Wireless Mouse", stock = 1)

        expectRefusal("Only 1") { saveOrder(line(plenty, 2), line(scarce, 2, name = "Wireless Mouse")) }

        assertEquals(20, stock(plenty))
        assertEquals(1, stock(scarce))
        assertEquals(0, showroom.observeOrders(buyerId).first().size)
    }

    @Test
    fun units_on_several_lines_of_one_order_are_added_up() = runBlocking {
        val id = addProduct(stock = 5)
        expectRefusal("Only 5") { saveOrder(line(id, 3), line(id, 3)) }
        saveOrder(line(id, 2), line(id, 3))
        assertEquals(0, stock(id))
        assertEquals(StockStatus.OUT_OF_STOCK, product(id).stockStatus)
    }

    @Test
    fun deleting_an_order_puts_its_items_back_unless_told_not_to() = runBlocking {
        val id = addProduct(stock = 20)
        val first = saveOrder(line(id, 3))
        val second = saveOrder(line(id, 2))
        assertEquals(15, stock(id))

        showroom.deleteOrder(first)
        assertEquals(18, stock(id))
        assertEquals(MovementType.SALE_RETURN, movements(id).last().type)

        showroom.deleteOrder(second, restock = false) // e.g. delivered, or damaged
        assertEquals(18, stock(id))
    }

    @Test
    fun deleting_a_buyer_puts_their_items_back() = runBlocking {
        val id = addProduct(stock = 20)
        saveOrder(line(id, 3))
        saveOrder(line(id, 4))
        showroom.deleteBuyer(buyerId)
        assertEquals(20, stock(id))
    }

    @Test
    fun custom_items_never_touch_stock() = runBlocking {
        val id = addProduct(stock = 20)
        saveOrder(line(null, 50, name = "USB Keyboard"))
        assertEquals(20, stock(id))
    }

    // ---- Availability and archiving ---------------------------------------------------------

    @Test
    fun unavailable_discontinued_and_archived_products_cannot_be_sold() = runBlocking {
        val id = addProduct(stock = 20)
        val orderId = saveOrder(line(id, 2))

        inventory.setAvailability(id, Availability.UNAVAILABLE)
        assertEquals(StockStatus.UNAVAILABLE, product(id).stockStatus)
        expectRefusal("unavailable") { saveOrder(line(id, 1)) }
        // An order that already has it can still be saved unchanged, or with fewer.
        saveOrder(line(id, 2), id = orderId)
        saveOrder(line(id, 1), id = orderId)
        assertEquals(19, stock(id))

        inventory.setAvailability(id, Availability.DISCONTINUED)
        expectRefusal("discontinued") { saveOrder(line(id, 1)) }

        inventory.setAvailability(id, Availability.AVAILABLE)
        inventory.setArchived(id, true)
        assertEquals(StockStatus.ARCHIVED, product(id).stockStatus)
        expectRefusal("archived") { saveOrder(line(id, 1)) }
        expectRefusal("Restore") { inventory.setAvailability(id, Availability.AVAILABLE) }
        expectRefusal("Restore") { inventory.stockIn(id, 5, clock.now, "", "", "") }
    }

    @Test
    fun a_product_with_sales_can_only_be_archived_and_keeps_its_history() = runBlocking {
        val sold = addProduct(name = "USB Keyboard", stock = 20)
        val unsold = addProduct(name = "Wireless Mouse", stock = 4)
        saveOrder(line(sold, 1))

        expectRefusal("Archive it instead") { inventory.deleteProduct(sold) }
        inventory.setArchived(sold, true)
        assertTrue(product(sold).archived)
        assertEquals(1, inventory.observeSales(sold).first().size)

        inventory.deleteProduct(unsold)
        assertNull(db.inventoryDao().getProduct(unsold))
        assertTrue(movements(unsold).isEmpty())
    }

    // ---- Low-stock alerts -------------------------------------------------------------------

    @Test
    fun a_low_stock_alert_is_recorded_once_per_dip_and_again_on_running_out() = runBlocking {
        val id = addProduct(stock = 7, min = 5)
        suspend fun alerts() = inventory.notifications.first().filter { it.kind == NotificationKind.LOW_STOCK }

        saveOrder(line(id, 2)) // 5: at the minimum
        saveOrder(line(id, 1)) // 4: still low, no second alert
        assertEquals(1, alerts().size)
        assertEquals("USB Keyboard is low in stock. Only 5 pc remain (minimum 5).", alerts().single().message)

        saveOrder(line(id, 4)) // 0: running out is worth its own alert
        assertEquals(2, alerts().size)
        assertEquals("Out of stock: USB Keyboard", alerts().first().title)

        inventory.stockIn(id, 10, clock.now, "", "", "") // 10: back above the minimum
        assertFalse(product(id).lowStockAlerted)
        saveOrder(line(id, 9)) // 1: a new dip
        assertEquals(3, alerts().size)
        assertEquals("USB Keyboard is low in stock. Only 1 pc remains (minimum 5).", alerts().first().message)
    }

    // ---- "May stock pa" reminders -----------------------------------------------------------

    @Test
    fun an_every_3_days_reminder_comes_once_per_interval() = runBlocking {
        val id = addProduct(stock = 25)
        inventory.setReminder(id, ReminderInterval.EVERY_3_DAYS)
        assertEquals(clock.now.plus(Duration.ofDays(3)), product(id).reminderNextAt)

        clock.now = clock.now.plus(Duration.ofDays(2))
        assertEquals(0, inventory.recordDueReminders())

        clock.now = clock.now.plus(Duration.ofDays(1))
        assertEquals(1, inventory.recordDueReminders())
        assertEquals(0, inventory.recordDueReminders()) // run twice: nothing new

        val reminder = inventory.notifications.first().single()
        assertEquals(NotificationKind.REMAINING_STOCK, reminder.kind)
        assertEquals("May stock pa: USB Keyboard", reminder.title)
        assertTrue(reminder.message.contains("25 pc left"))
        assertEquals(clock.now, product(id).reminderLastSentAt)
        assertEquals(clock.now.plus(Duration.ofDays(3)), product(id).reminderNextAt)
    }

    @Test
    fun weekly_and_monthly_reminders_follow_their_interval() = runBlocking {
        val weekly = addProduct(name = "USB Keyboard", stock = 5, min = 1)
        val monthly = addProduct(name = "Wireless Mouse", stock = 5, min = 1)
        inventory.setReminder(weekly, ReminderInterval.WEEKLY)
        inventory.setReminder(monthly, ReminderInterval.MONTHLY)
        assertEquals(Instant.parse("2026-10-27T01:00:00Z"), product(weekly).reminderNextAt)
        assertEquals(Instant.parse("2026-11-20T01:00:00Z"), product(monthly).reminderNextAt)

        clock.now = Instant.parse("2026-10-27T01:00:00Z")
        assertEquals(1, inventory.recordDueReminders())
        assertEquals(Instant.parse("2026-11-03T01:00:00Z"), product(weekly).reminderNextAt)
        clock.now = Instant.parse("2026-11-20T01:00:00Z")
        assertEquals(2, inventory.recordDueReminders()) // the monthly one, and the weekly one (overdue since Nov 3)
        assertEquals(Instant.parse("2026-12-20T01:00:00Z"), product(monthly).reminderNextAt)
        assertEquals(Instant.parse("2026-11-27T01:00:00Z"), product(weekly).reminderNextAt)
    }

    @Test
    fun changing_the_interval_reschedules_and_never_stops_reminders() = runBlocking {
        val id = addProduct(stock = 25)
        inventory.setReminder(id, ReminderInterval.EVERY_3_DAYS)
        clock.now = clock.now.plus(Duration.ofDays(3))
        inventory.recordDueReminders()
        val sent = clock.now

        inventory.setReminder(id, ReminderInterval.WEEKLY)
        assertEquals(sent.plus(Duration.ofDays(7)), product(id).reminderNextAt)

        inventory.setReminder(id, ReminderInterval.NEVER)
        assertNull(product(id).reminderNextAt)
        clock.now = clock.now.plus(Duration.ofDays(60))
        assertEquals(0, inventory.recordDueReminders())
        assertEquals(1, inventory.notifications.first().size)
    }

    @Test
    fun no_reminder_while_out_of_stock_archived_or_discontinued() = runBlocking {
        val empty = addProduct(name = "Empty", stock = 0)
        val archived = addProduct(name = "Archived", stock = 9)
        val discontinued = addProduct(name = "Discontinued", stock = 9)
        listOf(empty, archived, discontinued).forEach { inventory.setReminder(it, ReminderInterval.EVERY_3_DAYS) }
        inventory.setArchived(archived, true)
        inventory.setAvailability(discontinued, Availability.DISCONTINUED)

        clock.now = clock.now.plus(Duration.ofDays(10))
        assertEquals(0, inventory.recordDueReminders())

        // Restocked: the overdue reminder comes on the next run.
        inventory.stockIn(empty, 4, clock.now, "", "", "")
        assertEquals(1, inventory.recordDueReminders())
    }

    @Test
    fun notifications_are_handed_to_the_phone_once() = runBlocking {
        val id = addProduct(stock = 6, min = 5)
        saveOrder(line(id, 2)) // a low-stock alert
        val pending = inventory.unpostedNotifications()
        assertEquals(1, pending.size)

        inventory.markPosted(pending.map { it.id })
        assertTrue(inventory.unpostedNotifications().isEmpty())

        assertEquals(1, inventory.unreadNotifications.first())
        inventory.markNotificationsRead()
        assertEquals(0, inventory.unreadNotifications.first())
    }

    // ---- Reports ----------------------------------------------------------------------------

    @Test
    fun the_full_export_snapshot_includes_the_inventory() = runBlocking {
        val id = addProduct(stock = 20)
        saveOrder(line(id, 3))
        val snapshot = showroom.getExportSnapshot()
        assertEquals(listOf("USB Keyboard"), snapshot.products.map { it.name })
        assertEquals(listOf(MovementType.INITIAL, MovementType.SALE), snapshot.movements.map { it.movement.type })
        assertEquals(17, snapshot.inventory!!.totalUnits)

        val report = inventory.getProductReport(id)
        assertEquals(1, report.sales.size)
        assertEquals(2, report.movements.size)
    }
}
