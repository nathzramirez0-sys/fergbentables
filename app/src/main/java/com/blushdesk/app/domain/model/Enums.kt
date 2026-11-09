package com.blushdesk.app.domain.model

/**
 * How the buyer paid. [ONLINE_PAYMENT] only records the method (bank transfer, e-wallet, card
 * terminal, ...); the app never performs or verifies a transaction and needs no internet for it.
 */
enum class PaymentMode(val label: String) {
    CASH("Cash"),
    ONLINE_PAYMENT("Online payment"),
}

/**
 * Follows from the order's payments: [UNPAID] nothing received, [PENDING] part of the total
 * received (shown as "Partly paid"), [PAID] the whole total received. Only [PAID] orders may have an
 * official receipt; the others can have an order slip showing the balance.
 *
 * [PENDING] keeps its stored name from before payments were tracked, when it meant "promised"; the
 * version 6 migration turned those orders into [UNPAID], since no amount had been recorded.
 */
enum class PaymentStatus(val label: String) {
    UNPAID("Unpaid"),
    PENDING("Partly paid"),
    PAID("Paid");

    val isPaid: Boolean get() = this == PAID
}

/**
 * Order lifecycle. The declaration order is the progression order, so [next] and the visual
 * progress component both read it from [entries]; a one-tap advance never skips a stage.
 */
enum class FulfillmentStatus(val label: String) {
    PROCESSING("Processing"),
    PREPARING("Preparing"),
    DELIVERED("Delivered");

    /** The stage a one-tap advance moves to, or null once delivered. */
    val next: FulfillmentStatus? get() = entries.getOrNull(ordinal + 1)
}
