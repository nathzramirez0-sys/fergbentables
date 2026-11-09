package com.blushdesk.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.TestData
import com.blushdesk.app.TestData.addOrder
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.local.database.ShowroomDao
import com.blushdesk.app.data.repository.OfflineShowroomRepository
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.utils.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant

/** The real SQL against a real (in-memory) SQLite, with foreign keys on as in production. */
@RunWith(AndroidJUnit4::class)
class ShowroomDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ShowroomDao

    @Before
    fun create() {
        db = TestData.inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        dao = db.dao()
    }

    @After
    fun close() = db.close()

    private fun search(q: String) = runBlocking {
        dao.searchBuyers(OfflineShowroomRepository.likePattern(q)).first().map { it.buyer.fullName }
    }

    // ---- CRUD and types ----------------------------------------------------------------------

    @Test
    fun buyer_round_trips_with_all_fields() = runBlocking {
        val id = dao.insertBuyer(TestData.buyer("Ana Reyes"))
        val loaded = dao.getBuyer(id)!!
        assertEquals("Ana Reyes", loaded.fullName)
        assertEquals("0917 123 4567", loaded.contactNumber)
        assertEquals(TestData.buyer().dateAdded, loaded.dateAdded)
        assertEquals(loaded.dateAdded, loaded.createdAt)
    }

    @Test
    fun order_round_trips_money_enums_and_time_exactly() = runBlocking {
        val buyerId = dao.insertBuyer(TestData.buyer())
        val orderId = dao.addOrder(buyerId, unit = "999.99", qty = 3)
        val loaded = dao.getOrder(orderId)!!
        val line = dao.getOrderWithItems(orderId)!!.items.single()
        assertEquals(BigDecimal("999.99"), line.unitPrice)
        assertEquals(BigDecimal("2999.97"), line.lineTotal)
        assertEquals(BigDecimal("2999.97"), loaded.totalAmount)
        assertEquals(PaymentStatus.PAID, loaded.paymentStatus)
        assertEquals(FulfillmentStatus.DELIVERED, loaded.fulfillmentStatus)
        assertEquals(TestData.order(buyerId).purchaseDateTime, loaded.purchaseDateTime)
    }

    @Test
    fun deleting_a_buyer_cascades_to_their_orders_only() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer("Ana"))
        val ben = dao.insertBuyer(TestData.buyer("Ben"))
        dao.addOrder(ana)
        dao.addOrder(ana, product = "Chair")
        val bensOrder = dao.addOrder(ben)

        dao.deleteBuyer(ana)

        assertNull(dao.getBuyer(ana))
        assertEquals(1, dao.getAllBuyersWithOrders().single().orders.size)
        assertNotNull(dao.getOrder(bensOrder))
    }

    @Test
    fun an_order_for_a_missing_buyer_is_rejected_by_the_foreign_key() = runBlocking {
        try {
            dao.insertOrder(TestData.order(buyerId = 999)) // header only: no buyer 999
            fail("expected the foreign key to reject an orphan order")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun operator_is_one_row_that_upsert_replaces() = runBlocking {
        assertNull(dao.getOperator())
        dao.upsertOperator(TestData.operator)
        dao.upsertOperator(TestData.operator.copy(storeName = "Renamed"))
        assertEquals("Renamed", dao.observeOperator().first()!!.storeName)
        assertEquals("Lia Santos", dao.getOperator()!!.fullName)
    }

    @Test
    fun targeted_updates_change_one_order_and_stamp_updated_at() = runBlocking {
        val buyer = dao.insertBuyer(TestData.buyer())
        val target = dao.addOrder(buyer, pay = PaymentStatus.PENDING, stage = FulfillmentStatus.PROCESSING)
        val other = dao.addOrder(buyer, pay = PaymentStatus.PENDING, stage = FulfillmentStatus.PROCESSING)
        val later = Instant.parse("2026-10-05T00:00:00Z")

        dao.updateFulfillmentStatus(target, FulfillmentStatus.PREPARING, later)
        dao.updatePaymentStatus(target, PaymentStatus.PAID, later)

        val changed = dao.getOrder(target)!!
        assertEquals(FulfillmentStatus.PREPARING, changed.fulfillmentStatus)
        assertEquals(PaymentStatus.PAID, changed.paymentStatus)
        assertEquals(later, changed.updatedAt)
        assertEquals(FulfillmentStatus.PROCESSING, dao.getOrder(other)!!.fulfillmentStatus)
        assertEquals(PaymentStatus.PENDING, dao.getOrder(other)!!.paymentStatus)
    }

    // ---- Buyer list and search ---------------------------------------------------------------

    @Test
    fun buyer_list_shows_order_count_and_the_latest_orders_statuses() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer("Ana"))
        dao.insertBuyer(TestData.buyer("Ben"))
        dao.addOrder(ana, pay = PaymentStatus.PAID, stage = FulfillmentStatus.DELIVERED, at = "2026-09-01T00:00:00Z")
        dao.addOrder(ana, pay = PaymentStatus.PENDING, stage = FulfillmentStatus.PREPARING, at = "2026-10-01T00:00:00Z")

        val list = dao.searchBuyers("%").first()

        assertEquals(listOf("Ana", "Ben"), list.map { it.buyer.fullName })
        assertEquals(2, list[0].orderCount)
        assertEquals(FulfillmentStatus.PREPARING, list[0].latestFulfillmentStatus) // the October order
        assertEquals(PaymentStatus.PENDING, list[0].latestPaymentStatus)
        assertEquals(0, list[1].orderCount)
        assertNull(list[1].latestFulfillmentStatus)
        assertNull(list[1].latestPaymentStatus)
    }

    @Test
    fun search_matches_name_phone_or_email_case_insensitively() = runBlocking {
        dao.insertBuyer(TestData.buyer("Ana Reyes", email = "ana@example.com"))
        dao.insertBuyer(TestData.buyer("Ben Cruz", email = "bc@shop.example"))

        assertEquals(listOf("Ana Reyes"), search("ANA"))
        assertEquals(listOf("Ben Cruz"), search("shop.example"))
        assertEquals(listOf("Ana Reyes", "Ben Cruz"), search("0917 123"))
        assertEquals(emptyList<String>(), search("nobody"))
    }

    @Test
    fun search_also_matches_the_facebook_name() = runBlocking {
        dao.insertBuyer(TestData.buyer("Ana Reyes").copy(contactNumber = "", facebookName = "Ana Blush Home"))
        dao.insertBuyer(TestData.buyer("Ben Cruz", email = "bc@shop.example"))

        assertEquals(listOf("Ana Reyes"), search("blush home"))
    }

    @Test
    fun search_treats_percent_and_underscore_literally() = runBlocking {
        dao.insertBuyer(TestData.buyer("Ana 50% Off"))
        dao.insertBuyer(TestData.buyer("Ben Cruz"))
        dao.insertBuyer(TestData.buyer("Cy_Lo"))

        assertEquals(listOf("Ana 50% Off"), search("%"))
        assertEquals(listOf("Cy_Lo"), search("_"))
        assertEquals(listOf("Cy_Lo"), search("y_l"))
    }

    // ---- Orders and totals -------------------------------------------------------------------

    @Test
    fun buyer_orders_are_newest_first() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer())
        dao.addOrder(ana, product = "Old", at = "2026-09-01T00:00:00Z")
        dao.addOrder(ana, product = "New", at = "2026-10-01T00:00:00Z")
        assertEquals(listOf("New", "Old"), dao.observeOrdersForBuyer(ana).first().map { it.items.first().productName })
    }

    @Test
    fun order_totals_split_paid_from_outstanding() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer())
        dao.addOrder(ana, unit = "1000.00", qty = 1, pay = PaymentStatus.PAID, stage = FulfillmentStatus.DELIVERED)
        dao.addOrder(ana, unit = "500.00", qty = 2, pay = PaymentStatus.PENDING, stage = FulfillmentStatus.PREPARING) // ₱500 paid of ₱1,000
        dao.addOrder(ana, unit = "100.00", qty = 1, pay = PaymentStatus.UNPAID, stage = FulfillmentStatus.PROCESSING)

        val totals = dao.observeOrderTotals(ana).first()

        assertEquals(3, totals.orderCount)
        assertEquals(2, totals.openOrders)
        assertEquals(Money.of("1500.00"), totals.paidAmount)
        assertEquals(Money.of("600.00"), totals.outstandingAmount)
        assertEquals(Money.of("2100.00"), totals.totalAmount)
    }

    @Test
    fun a_payment_above_the_total_never_counts_twice() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer())
        // Paid in full, then the order was edited down to ₱800.
        val order = dao.addOrder(ana, unit = "1000.00", qty = 1, pay = PaymentStatus.PAID)
        dao.updateOrder(dao.getOrder(order)!!.copy(totalAmount = Money.of("800.00")))

        val totals = dao.observeOrderTotals(ana).first()
        val summary = dao.observeShowroomSummary().first()

        assertEquals(Money.of("800.00"), totals.paidAmount)
        assertEquals(Money.ZERO, totals.outstandingAmount)
        assertEquals(Money.of("800.00"), summary.paidAmount)
        assertEquals(Money.ZERO, summary.outstandingAmount)
    }

    @Test
    fun totals_of_a_buyer_without_orders_are_zero() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer())
        val totals = dao.observeOrderTotals(ana).first()
        assertEquals(0, totals.orderCount)
        assertEquals(Money.ZERO, totals.totalAmount)
    }

    @Test
    fun orders_can_be_retrieved_by_payment_and_by_fulfillment_status() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer())
        val a = dao.addOrder(ana, product = "A", pay = PaymentStatus.UNPAID, stage = FulfillmentStatus.PROCESSING)
        val b = dao.addOrder(ana, product = "B", pay = PaymentStatus.PAID, stage = FulfillmentStatus.PREPARING)
        val c = dao.addOrder(ana, product = "C", pay = PaymentStatus.UNPAID, stage = FulfillmentStatus.PREPARING)

        assertEquals(listOf(a, c), dao.observeOrdersByPaymentStatus(PaymentStatus.UNPAID).first().map { it.id })
        assertEquals(listOf(b), dao.observeOrdersByPaymentStatus(PaymentStatus.PAID).first().map { it.id })
        assertEquals(listOf(b, c), dao.observeOrdersByFulfillmentStatus(FulfillmentStatus.PREPARING).first().map { it.id })
        assertEquals(emptyList<Long>(), dao.observeOrdersByFulfillmentStatus(FulfillmentStatus.DELIVERED).first().map { it.id })
    }

    @Test
    fun showroom_summary_counts_buyers_orders_and_amounts() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer("Ana"))
        dao.insertBuyer(TestData.buyer("Ben"))
        dao.addOrder(ana, unit = "2000.00", qty = 1, pay = PaymentStatus.PAID, stage = FulfillmentStatus.DELIVERED)
        dao.addOrder(ana, unit = "50.00", qty = 1, pay = PaymentStatus.UNPAID, stage = FulfillmentStatus.PROCESSING)

        val summary = dao.observeShowroomSummary().first()

        assertEquals(2, summary.buyerCount)
        assertEquals(2, summary.orderCount)
        assertEquals(1, summary.openOrders)
        assertEquals(Money.of("2000.00"), summary.paidAmount)
        assertEquals(Money.of("50.00"), summary.outstandingAmount)
    }

    @Test
    fun export_summary_counts_every_status_and_total_sales() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer("Ana"))
        dao.insertBuyer(TestData.buyer("Ben"))
        dao.addOrder(ana, unit = "100.00", qty = 1, pay = PaymentStatus.PAID, stage = FulfillmentStatus.DELIVERED)
        dao.addOrder(ana, unit = "200.00", qty = 1, pay = PaymentStatus.PENDING, stage = FulfillmentStatus.PREPARING)
        dao.addOrder(ana, unit = "300.00", qty = 1, pay = PaymentStatus.UNPAID, stage = FulfillmentStatus.PROCESSING)
        dao.addOrder(ana, unit = "400.00", qty = 1, pay = PaymentStatus.UNPAID, stage = FulfillmentStatus.PROCESSING)

        val s = dao.getExportSummary()

        assertEquals(2, s.totalBuyers)
        assertEquals(4, s.totalOrders)
        assertEquals(1, s.paidOrders)
        assertEquals(2, s.unpaidOrders)
        assertEquals(1, s.pendingOrders)
        assertEquals(2, s.processingOrders)
        assertEquals(1, s.preparingOrders)
        assertEquals(1, s.deliveredOrders)
        assertEquals(Money.of("1000.00"), s.totalRecordedSales)
    }

    @Test
    fun relation_returns_each_buyers_own_orders() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer("Ana"))
        val ben = dao.insertBuyer(TestData.buyer("Ben"))
        dao.addOrder(ana, product = "Sofa")
        dao.addOrder(ana, product = "Table")
        dao.addOrder(ben, product = "Lamp")

        val all = dao.getAllBuyersWithOrders()
        assertEquals(listOf("Ana", "Ben"), all.map { it.buyer.fullName })
        assertEquals(setOf("Sofa", "Table"), all[0].orders.map { it.items.single().productName }.toSet())
        assertEquals(listOf("Lamp"), all[1].orders.map { it.items.single().productName })
    }

    // ---- Order items -------------------------------------------------------------------------

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun an_order_keeps_several_items_in_entry_order() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer())
        val id = dao.addOrder(ana, items = listOf(TestData.item("Sofa", "100.00", 1), TestData.item("Pillow", "20.00", 3), TestData.item("Rug", "50.00", 1)))

        val loaded = dao.getOrderWithItems(id)!!
        assertEquals(listOf("Sofa", "Pillow", "Rug"), loaded.items.map { it.productName })
        assertEquals(listOf(0, 1, 2), loaded.items.map { it.position })
        assertEquals(Money.of("210.00"), loaded.order.totalAmount)
        assertEquals(5, loaded.unitCount)
        assertEquals(3, dao.observeOrdersForBuyer(ana).first().single().items.size)
    }

    @Test
    fun deleting_an_order_deletes_its_items_and_deleting_a_buyer_deletes_both() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer("Ana"))
        val first = dao.addOrder(ana, items = listOf(TestData.item("A"), TestData.item("B")))
        dao.addOrder(ana, items = listOf(TestData.item("C")))
        assertEquals(3, count("order_items"))

        dao.deleteOrder(first)
        assertEquals(1, count("order_items"))

        dao.deleteBuyer(ana)
        assertEquals(0, count("orders"))
        assertEquals(0, count("order_items"))
    }

    @Test
    fun an_item_for_a_missing_order_is_rejected_by_the_foreign_key() = runBlocking {
        try {
            dao.insertItems(listOf(TestData.item(orderId = 999)))
            fail("expected the foreign key to reject an orphan item")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun replacing_an_orders_items_leaves_only_the_new_ones() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer())
        val id = dao.addOrder(ana, items = listOf(TestData.item("Old 1"), TestData.item("Old 2")))

        dao.deleteItemsOfOrder(id)
        dao.insertItems(listOf(TestData.item("New", orderId = id)))

        assertEquals(listOf("New"), dao.getOrderWithItems(id)!!.items.map { it.productName })
    }

    @Test
    fun export_relation_nests_items_inside_orders_inside_buyers() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer("Ana"))
        dao.addOrder(ana, items = listOf(TestData.item("Sofa"), TestData.item("Lamp")))
        val nested = dao.getAllBuyersWithOrders().single().orders.single()
        assertEquals(listOf("Sofa", "Lamp"), nested.items.map { it.productName })
    }

    @Test
    fun observing_a_buyer_emits_null_after_deletion() = runBlocking {
        val ana = dao.insertBuyer(TestData.buyer("Ana"))
        assertNotNull(dao.observeBuyer(ana).first())
        dao.deleteBuyer(ana)
        assertNull(dao.observeBuyer(ana).first())
    }
}
