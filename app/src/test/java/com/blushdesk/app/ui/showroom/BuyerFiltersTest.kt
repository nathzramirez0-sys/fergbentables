package com.blushdesk.app.ui.showroom

import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerListItem
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.components.buyerPaymentStatus
import com.blushdesk.app.utils.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BuyerFiltersTest {

    private fun buyer(id: Long, name: String, orders: Int, open: Int, balance: String, latest: PaymentStatus? = null) = BuyerListItem(
        buyer = Buyer(id = id, fullName = name, dateAdded = Instant.EPOCH),
        orderCount = orders,
        latestFulfillmentStatus = null,
        latestPaymentStatus = latest,
        openOrders = open,
        balance = Money.of(balance),
    )

    private val ana = buyer(1, "Ana Reyes", orders = 3, open = 2, balance = "25300.00", latest = PaymentStatus.PENDING)
    private val ben = buyer(2, "Ben Cruz", orders = 2, open = 2, balance = "9799.97", latest = PaymentStatus.UNPAID)
    private val carla = buyer(3, "carla Mendoza", orders = 3, open = 1, balance = "0.00", latest = PaymentStatus.PAID)
    private val dan = buyer(4, "Dan", orders = 0, open = 0, balance = "0.00")
    private val all = listOf(dan, carla, ben, ana)

    @Test
    fun `filters keep who owes and who has something to deliver`() {
        assertEquals(listOf("Ana Reyes", "Ben Cruz", "carla Mendoza", "Dan"), all.filteredAndSorted(BuyerFilter.ALL, BuyerSort.NAME).map { it.buyer.fullName })
        assertEquals(listOf("Ana Reyes", "Ben Cruz"), all.filteredAndSorted(BuyerFilter.BALANCE_DUE, BuyerSort.NAME).map { it.buyer.fullName })
        assertEquals(listOf("Ana Reyes", "Ben Cruz", "carla Mendoza"), all.filteredAndSorted(BuyerFilter.TO_DELIVER, BuyerSort.NAME).map { it.buyer.fullName })
    }

    @Test
    fun `most due puts the biggest balance first, then by name`() {
        assertEquals(listOf("Ana Reyes", "Ben Cruz", "carla Mendoza", "Dan"), all.filteredAndSorted(BuyerFilter.ALL, BuyerSort.MOST_DUE).map { it.buyer.fullName })
    }

    @Test
    fun `each buyer gets one payment chip`() {
        assertEquals(PaymentStatus.PENDING, buyerPaymentStatus(ana))
        assertEquals(PaymentStatus.UNPAID, buyerPaymentStatus(ben))
        assertEquals(PaymentStatus.PAID, buyerPaymentStatus(carla))
    }
}
