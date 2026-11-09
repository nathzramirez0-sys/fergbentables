package com.blushdesk.app

import android.content.Context
import androidx.room.Room
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerWithOrders
import com.blushdesk.app.data.local.database.ExportSummary
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.data.local.database.Payment
import com.blushdesk.app.data.local.database.ShowroomDao
import com.blushdesk.app.domain.model.ExportSnapshot
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.utils.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Shared fixtures for the instrumented tests. */
object TestData {
    val operator = OperatorProfile(
        fullName = "Lia Santos",
        storeName = "Rosé Showroom",
        email = "lia@rose.example",
        phoneNumber = "0917 555 0100",
    )

    fun inMemoryDatabase(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    fun buyer(name: String = "Ana Reyes", id: Long = 0, email: String = "ana@example.com") = Buyer(
        id = id,
        fullName = name,
        contactNumber = "0917 123 4567",
        email = email,
        dateAdded = Instant.parse("2026-09-01T02:00:00Z"),
    )

    fun item(product: String = "Velvet Sofa", unit: String = "12500.50", qty: Int = 2, position: Int = 0, orderId: Long = 0) =
        OrderItem(orderId = orderId, position = position, productName = product, unitPrice = Money.of(unit), quantity = qty)

    /** An order header; [total] is what the repository would compute from the items. */
    fun order(
        buyerId: Long,
        pay: PaymentStatus = PaymentStatus.PAID,
        stage: FulfillmentStatus = FulfillmentStatus.DELIVERED,
        mode: PaymentMode = PaymentMode.ONLINE_PAYMENT,
        id: Long = 0,
        at: String = "2026-09-20T03:00:00Z",
        total: String = "0.00",
    ) = Order(
        id = id,
        buyerId = buyerId,
        totalAmount = Money.of(total),
        purchaseDateTime = Instant.parse(at),
        paymentMode = mode,
        paymentStatus = pay,
        fulfillmentStatus = stage,
    )

    /** An order with its lines, the total computed from them; ids are set for use without a database. */
    fun orderWithItems(
        id: Long,
        buyerId: Long,
        items: List<OrderItem>,
        pay: PaymentStatus = PaymentStatus.PAID,
        stage: FulfillmentStatus = FulfillmentStatus.DELIVERED,
        mode: PaymentMode = PaymentMode.ONLINE_PAYMENT,
        at: String = "2026-09-20T03:00:00Z",
    ): OrderWithItems {
        val lines = items.mapIndexed { i, item -> item.copy(id = id * 100 + i, orderId = id, position = i) }
        val total = lines.fold(Money.ZERO) { sum, line -> sum + line.lineTotal }
        return OrderWithItems(order(buyerId, pay, stage, mode, id, at).copy(totalAmount = total), lines)
    }

    /**
     * Inserts an order and its lines straight through the DAO (bypassing the repository), with the
     * total set the way the repository would set it. One line by default.
     *
     * The payments match [pay], since the totals are worked out from payments: a paid order gets
     * one payment of its total, a partly paid one a payment of half of it, an unpaid one none.
     */
    suspend fun ShowroomDao.addOrder(
        buyerId: Long,
        product: String = "Velvet Sofa",
        unit: String = "12500.50",
        qty: Int = 2,
        pay: PaymentStatus = PaymentStatus.PAID,
        stage: FulfillmentStatus = FulfillmentStatus.DELIVERED,
        mode: PaymentMode = PaymentMode.ONLINE_PAYMENT,
        at: String = "2026-09-20T03:00:00Z",
        items: List<OrderItem> = listOf(item(product, unit, qty)),
    ): Long {
        val total = items.fold(Money.ZERO) { sum, line -> sum + line.lineTotal }
        val id = insertOrder(order(buyerId, pay, stage, mode, at = at).copy(totalAmount = total))
        insertItems(items.mapIndexed { i, line -> line.copy(orderId = id, position = i) })
        val paid = when (pay) {
            PaymentStatus.PAID -> total
            PaymentStatus.PENDING -> total.divide(BigDecimal(2), 2, RoundingMode.DOWN)
            PaymentStatus.UNPAID -> Money.ZERO
        }
        if (paid.signum() > 0) insertPayment(Payment(orderId = id, amount = paid, paidAt = Instant.parse(at), paymentMode = mode))
        return id
    }

    /** Two buyers, three orders (one with two products), every status represented. */
    fun sampleSnapshot(): ExportSnapshot {
        val ana = buyer("Ana Reyes", id = 1)
        val ben = buyer("Ben Cruz", id = 2, email = "")
        val buyers = listOf(
            BuyerWithOrders(
                ana,
                listOf(
                    orderWithItems(10, 1, listOf(item("Velvet Sofa", "12500.50", 2)), PaymentStatus.PAID, FulfillmentStatus.DELIVERED, PaymentMode.ONLINE_PAYMENT, "2026-09-20T03:00:00Z"),
                    orderWithItems(
                        11, 1, listOf(item("Side Table", "3500", 1), item("Throw Pillow", "450", 4)),
                        PaymentStatus.PENDING, FulfillmentStatus.PREPARING, PaymentMode.CASH, "2026-10-01T03:00:00Z",
                    ),
                ),
            ),
            BuyerWithOrders(
                ben,
                listOf(orderWithItems(12, 2, listOf(item("Floor Lamp", "999.99", 3)), PaymentStatus.UNPAID, FulfillmentStatus.PROCESSING, PaymentMode.CASH, "2026-09-25T03:00:00Z")),
            ),
        )
        val summary = ExportSummary(2, 3, 1, 1, 1, 1, 1, 1, Money.of("33300.97"))
        return ExportSnapshot(operator, buyers, summary, Instant.parse("2026-10-02T07:45:00Z"))
    }
}
