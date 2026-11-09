package com.blushdesk.app.utils

import com.blushdesk.app.data.local.database.Payment
import com.blushdesk.app.domain.model.PaymentStatus
import java.math.BigDecimal

/** Money received against an order: what has been paid, what is left, and the status that follows. */
object Payments {

    fun paid(payments: List<Payment>): BigDecimal = payments.fold(Money.ZERO) { sum, payment -> sum + payment.amount }

    /** The total minus what was paid, never below zero (an order edited down after a payment owes nothing). */
    fun balance(total: BigDecimal, paid: BigDecimal): BigDecimal = (total - paid).max(Money.ZERO)

    /** Nothing paid is UNPAID, part of the total PENDING ("Partly paid"), all of it PAID. */
    fun status(total: BigDecimal, paid: BigDecimal): PaymentStatus = when {
        paid >= total -> PaymentStatus.PAID
        paid.signum() > 0 -> PaymentStatus.PENDING
        else -> PaymentStatus.UNPAID
    }

    /** Why [amount] can't be recorded against a [balance], or null when it can. */
    fun amountProblem(amount: BigDecimal?, balance: BigDecimal): String? = when {
        amount == null -> "Enter the amount received, like 5000 or 5000.50."
        amount.signum() <= 0 -> "Enter an amount above ₱0.00."
        amount > balance -> "That is more than the balance of ${Money.format(balance)}."
        else -> null
    }
}
