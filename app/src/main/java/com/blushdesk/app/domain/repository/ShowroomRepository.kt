package com.blushdesk.app.domain.repository

import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerListItem
import com.blushdesk.app.data.local.database.OpenOrder
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderTotals
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.domain.model.BuyerReport
import com.blushdesk.app.domain.model.ExportSnapshot
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.utils.Money
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant

/**
 * The only door to the showroom's data. ViewModels depend on this interface, never on Room, so
 * tests can use an in-memory fake and storage can change without touching the UI.
 *
 * Saving functions validate their input and throw
 * [com.blushdesk.app.domain.model.UserFacingException] with a readable message when it is wrong.
 */
interface ShowroomRepository {
    /** The active operator, or a blank profile until one is set up. Never null. */
    val operator: Flow<OperatorProfile>
    val summary: Flow<ShowroomSummary>

    /** Every product, for the order form's product picker (it offers only the sellable ones). */
    val products: Flow<List<Product>>

    fun searchBuyers(query: String): Flow<List<BuyerListItem>>

    /** Orders not delivered yet, across every buyer, soonest first (the Today view). */
    val openOrders: Flow<List<OpenOrder>>
    fun observeBuyer(buyerId: Long): Flow<Buyer?>
    fun observeOrders(buyerId: Long): Flow<List<OrderWithItems>>
    fun observeOrderTotals(buyerId: Long): Flow<OrderTotals>

    suspend fun getOperator(): OperatorProfile
    suspend fun getBuyer(buyerId: Long): Buyer?
    suspend fun getOrder(orderId: Long): OrderWithItems?
    suspend fun getExportSnapshot(): ExportSnapshot
    suspend fun getBuyerReport(buyerId: Long): BuyerReport

    suspend fun saveOperator(profile: OperatorProfile)

    /** Inserts when [Buyer.id] is 0, otherwise updates. Returns the buyer's id. */
    suspend fun saveBuyer(buyer: Buyer): Long

    /** Deletes the buyer and their orders; with [restock], their inventory items go back in stock. */
    suspend fun deleteBuyer(buyerId: Long, restock: Boolean = true)

    /**
     * Inserts when [Order.id] is 0, otherwise updates, together with its product lines (which
     * replace any previous ones). Line totals and the order total are always recomputed. Returns the id.
     *
     * Lines linked to a product take their quantity out of stock in the same transaction; an edit
     * only moves the difference, so saving the same order again never deducts twice. Not enough
     * stock, or a product that can't be sold, refuses the whole save.
     *
     * A new order can come with [paidNow], money received when it was made (a downpayment or the
     * full amount), recorded as its first payment in [Order.paymentMode]. The payment status always
     * follows from the payments: an edit that changes the total updates it too.
     */
    suspend fun saveOrder(order: Order, items: List<OrderItem>, paidNow: BigDecimal = Money.ZERO): Long

    /** Deletes the order; with [restock], its inventory items go back in stock (not when damaged). */
    suspend fun deleteOrder(orderId: Long, restock: Boolean = true)
    suspend fun setFulfillmentStatus(orderId: Long, status: FulfillmentStatus)

    /** Records money received for an order, no more than its balance. Returns the payment's id. */
    suspend fun recordPayment(orderId: Long, amount: BigDecimal, mode: PaymentMode, paidAt: Instant, note: String = ""): Long

    /** Records the whole balance as paid now, in the order's payment mode. Returns the payment's id, or null if nothing was owed. */
    suspend fun markPaid(orderId: Long): Long?

    /** Removes a payment recorded by mistake (or undone), and updates the order's status. */
    suspend fun deletePayment(paymentId: Long)

    /** Where and when the order is to be delivered; a blank address and null date clear them. */
    suspend fun setDelivery(orderId: Long, address: String, date: Instant?)
}
