package com.blushdesk.app.domain

import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.domain.model.isLowStock
import com.blushdesk.app.domain.model.stockStatusOf
import com.blushdesk.app.utils.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** The stock label and reminder scheduling rules, which every screen and export relies on. */
class InventoryRulesTest {

    private val manila = ZoneId.of("Asia/Manila")

    @Test
    fun `the quantity decides the status of a product for sale`() {
        assertEquals(StockStatus.IN_STOCK, stockStatusOf(20, 5, Availability.AVAILABLE, archived = false))
        assertEquals(StockStatus.LOW_STOCK, stockStatusOf(5, 5, Availability.AVAILABLE, archived = false))
        assertEquals(StockStatus.LOW_STOCK, stockStatusOf(1, 5, Availability.AVAILABLE, archived = false))
        assertEquals(StockStatus.OUT_OF_STOCK, stockStatusOf(0, 5, Availability.AVAILABLE, archived = false))
    }

    @Test
    fun `zero stock never reads as in stock, even with a minimum of zero`() {
        assertEquals(StockStatus.OUT_OF_STOCK, stockStatusOf(0, 0, Availability.AVAILABLE, archived = false))
        assertEquals(StockStatus.IN_STOCK, stockStatusOf(1, 0, Availability.AVAILABLE, archived = false))
    }

    @Test
    fun `withdrawn and archived products say so before any stock label`() {
        assertEquals(StockStatus.UNAVAILABLE, stockStatusOf(20, 5, Availability.UNAVAILABLE, archived = false))
        assertEquals(StockStatus.DISCONTINUED, stockStatusOf(0, 5, Availability.DISCONTINUED, archived = false))
        assertEquals(StockStatus.ARCHIVED, stockStatusOf(20, 5, Availability.AVAILABLE, archived = true))
    }

    @Test
    fun `low stock includes empty`() {
        assertTrue(isLowStock(0, 3))
        assertTrue(isLowStock(3, 3))
        assertFalse(isLowStock(4, 3))
    }

    @Test
    fun `only available products that are not archived can be sold`() {
        val p = Product(name = "USB Keyboard", sellingPrice = Money.of("850"), quantity = 20)
        assertTrue(p.isSellable)
        assertFalse(p.copy(availability = Availability.UNAVAILABLE).isSellable)
        assertFalse(p.copy(availability = Availability.DISCONTINUED).isSellable)
        assertFalse(p.copy(archived = true).isSellable)
        // Stock is checked separately, with a clear "only N left" message.
        assertTrue(p.copy(quantity = 0).isSellable)
    }

    @Test
    fun `reminders repeat every 3 days, every week or every month, in the shop's time zone`() {
        val sent = Instant.parse("2026-10-20T01:00:00Z") // 9:00 AM in Manila
        assertEquals(Instant.parse("2026-10-23T01:00:00Z"), ReminderInterval.EVERY_3_DAYS.next(sent, manila))
        assertEquals(Instant.parse("2026-10-27T01:00:00Z"), ReminderInterval.WEEKLY.next(sent, manila))
        assertEquals(Instant.parse("2026-11-20T01:00:00Z"), ReminderInterval.MONTHLY.next(sent, manila))
        assertNull(ReminderInterval.NEVER.next(sent, manila))
    }

    @Test
    fun `a monthly reminder from the 31st falls on the last day of a shorter month`() {
        val sent = Instant.parse("2026-01-31T01:00:00Z")
        assertEquals(Instant.parse("2026-02-28T01:00:00Z"), ReminderInterval.MONTHLY.next(sent, manila))
    }
}
