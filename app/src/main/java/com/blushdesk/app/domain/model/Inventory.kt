package com.blushdesk.app.domain.model

import java.time.Instant
import java.time.ZoneId

/**
 * Whether the showroom offers a product, set by the operator. It says nothing about how many are
 * left: that is [StockStatus], worked out from the quantity, so the two can never contradict
 * each other (a product with 0 left reads "Out of stock" even while it is [AVAILABLE]).
 */
enum class Availability(val label: String) {
    AVAILABLE("Available"),

    /** Kept in the catalog but not sold for now (on hold, display piece, ...). */
    UNAVAILABLE("Unavailable"),

    /** No longer carried. Remaining stock can still be counted and adjusted. */
    DISCONTINUED("Discontinued"),
}

/** What the inventory shows for a product, derived by [stockStatusOf]; never stored. */
enum class StockStatus(val label: String) {
    IN_STOCK("In stock"),
    LOW_STOCK("Low stock"),
    OUT_OF_STOCK("Out of stock"),
    UNAVAILABLE("Unavailable"),
    DISCONTINUED("Discontinued"),
    ARCHIVED("Archived"),
}

/**
 * The single rule behind every stock label. An archived or withdrawn product says so first;
 * otherwise the quantity decides: 0 is out of stock, at or below the minimum is low.
 */
fun stockStatusOf(quantity: Int, minimumStock: Int, availability: Availability, archived: Boolean): StockStatus = when {
    archived -> StockStatus.ARCHIVED
    availability == Availability.DISCONTINUED -> StockStatus.DISCONTINUED
    availability == Availability.UNAVAILABLE -> StockStatus.UNAVAILABLE
    quantity <= 0 -> StockStatus.OUT_OF_STOCK
    quantity <= minimumStock -> StockStatus.LOW_STOCK
    else -> StockStatus.IN_STOCK
}

/** At or below the minimum, including empty: what triggers a low-stock alert. */
fun isLowStock(quantity: Int, minimumStock: Int): Boolean = quantity <= minimumStock

/** Why a product's quantity changed. Every change is one stock movement row; none is silent. */
enum class MovementType(val label: String) {
    /** The quantity a product was created with. */
    INITIAL("Initial stock"),
    STOCK_IN("Stock in"),

    /** Taken by an order when it was saved (or when its quantity went up). */
    SALE("Sale"),

    /** Put back because an order was edited down or deleted. */
    SALE_RETURN("Returned from order"),

    /** A manual correction, with an [AdjustmentReason]. */
    ADJUSTMENT("Adjustment"),
}

/**
 * The reasons offered for a manual adjustment. [adds] says which way the quantity normally goes;
 * a count correction can go either way.
 */
enum class AdjustmentReason(val label: String, val adds: Boolean?) {
    COUNT_CORRECTION("Count correction", null),
    DAMAGED("Damaged", false),
    LOST("Lost or missing", false),
    FOUND("Found", true),
    CUSTOMER_RETURN("Customer return (resellable)", true),
    OTHER("Other", null),
}

/**
 * How often the "May stock pa" reminder repeats for a product. [next] adds the interval in the
 * shop's time zone, so a monthly reminder keeps its day of the month (Jan 31 -> Feb 28 -> Mar 28).
 */
enum class ReminderInterval(val label: String) {
    EVERY_3_DAYS("Every 3 days"),
    WEEKLY("Every 1 week"),
    MONTHLY("Every 1 month"),
    NEVER("Never");

    /** When the reminder after one sent at [from] is due, or null for [NEVER]. */
    fun next(from: Instant, zone: ZoneId): Instant? {
        val start = from.atZone(zone)
        return when (this) {
            EVERY_3_DAYS -> start.plusDays(3)
            WEEKLY -> start.plusWeeks(1)
            MONTHLY -> start.plusMonths(1)
            NEVER -> null
        }?.toInstant()
    }
}

/**
 * The two inventory notifications, deliberately separate: a reminder that stock is still on hand
 * (on the schedule the operator picked) and an alert that a product has run low (when it happens).
 */
enum class NotificationKind(val label: String) {
    REMAINING_STOCK("Stock reminder"),
    LOW_STOCK("Low-stock alert"),
}
