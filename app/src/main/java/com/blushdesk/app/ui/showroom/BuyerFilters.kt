package com.blushdesk.app.ui.showroom

import com.blushdesk.app.data.local.database.BuyerListItem

/** Which buyers the list shows. */
enum class BuyerFilter(val label: String) {
    ALL("All"),
    BALANCE_DUE("Balance due"),
    TO_DELIVER("To deliver"),
    ;

    fun matches(item: BuyerListItem): Boolean = when (this) {
        ALL -> true
        BALANCE_DUE -> item.balance.signum() > 0
        TO_DELIVER -> item.openOrders > 0
    }

    /** What to say when nobody fits. */
    val emptyMessage: String
        get() = when (this) {
            ALL -> "No buyers yet."
            BALANCE_DUE -> "Nobody owes anything. Every order is paid."
            TO_DELIVER -> "Nothing to deliver. Every order is delivered."
        }
}

/** How the buyer list is ordered. */
enum class BuyerSort(val label: String) {
    NAME("Name A–Z"),
    MOST_DUE("Most due"),
}

/** The buyers [filter] keeps, in [sort] order; ties fall back to the name. */
fun List<BuyerListItem>.filteredAndSorted(filter: BuyerFilter, sort: BuyerSort): List<BuyerListItem> {
    val byName = compareBy<BuyerListItem>({ it.buyer.fullName.lowercase() }, { it.buyer.id })
    val order = when (sort) {
        BuyerSort.NAME -> byName
        BuyerSort.MOST_DUE -> compareByDescending<BuyerListItem> { it.balance }.then(byName)
    }
    return filter(filter::matches).sortedWith(order)
}
