package com.blushdesk.app.utils

import com.blushdesk.app.data.local.database.Payment
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class PaymentsTest {

    private fun payment(amount: String) = Payment(orderId = 6, amount = Money.of(amount), paidAt = Instant.EPOCH, paymentMode = PaymentMode.CASH)

    @Test
    fun `status follows what was paid`() {
        val total = Money.of("26800.00")
        assertEquals(PaymentStatus.UNPAID, Payments.status(total, Money.ZERO))
        assertEquals(PaymentStatus.PENDING, Payments.status(total, Money.of("5000.00")))
        assertEquals(PaymentStatus.PAID, Payments.status(total, Money.of("26800.00")))
        assertEquals("Partly paid", PaymentStatus.PENDING.label)
    }

    @Test
    fun `balance is the total minus payments, never below zero`() {
        val paid = Payments.paid(listOf(payment("5000.00"), payment("1800.00")))
        assertEquals(Money.of("6800.00"), paid)
        assertEquals(Money.of("20000.00"), Payments.balance(Money.of("26800.00"), paid))
        // An order edited down after it was paid owes nothing, and counts as paid.
        assertEquals(Money.ZERO, Payments.balance(Money.of("5000.00"), paid))
        assertEquals(PaymentStatus.PAID, Payments.status(Money.of("5000.00"), paid))
    }

    @Test
    fun `an amount must be above zero and within the balance`() {
        val balance = Money.of("21800.00")
        assertEquals("Enter the amount received, like 5000 or 5000.50.", Payments.amountProblem(null, balance))
        assertEquals("Enter an amount above ₱0.00.", Payments.amountProblem(Money.ZERO, balance))
        assertEquals("That is more than the balance of ₱21,800.00.", Payments.amountProblem(Money.of("21800.01"), balance))
        assertNull(Payments.amountProblem(Money.of("21800.00"), balance))
    }

    @Test
    fun `order numbers carry the prefix they were made with`() {
        assertEquals("BD-000006", Formats.orderNumber(6))
        assertEquals("RS-000007", Formats.orderNumber(7, "RS"))
        assertEquals("BD-000008", Formats.orderNumber(8, " "))
    }

    @Test
    fun `a prefix is 1 to 4 letters or digits starting with a letter`() {
        assertNull(Validation.orderPrefix("RS"))
        assertNull(Validation.orderPrefix("FB24"))
        assertEquals("Enter a prefix, like BD or RS", Validation.orderPrefix(""))
        assertEquals("Use 1 to 4 letters or digits, starting with a letter", Validation.orderPrefix("2RS"))
        assertEquals("Use 1 to 4 letters or digits, starting with a letter", Validation.orderPrefix("ROSES"))
        assertEquals("Use 1 to 4 letters or digits, starting with a letter", Validation.orderPrefix("R-S"))
    }
}
