package com.blushdesk.app.domain.repository

import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerListItem
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
import com.blushdesk.app.domain.model.PaymentStatus
import kotlinx.coroutines.flow.Flow

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
     */
    suspend fun saveOrder(order: Order, items: List<OrderItem>): Long

    /** Deletes the order; with [restock], its inventory items go back in stock (not when damaged). */
    suspend fun deleteOrder(orderId: Long, restock: Boolean = true)
    suspend fun setFulfillmentStatus(orderId: Long, status: FulfillmentStatus)
    suspend fun setPaymentStatus(orderId: Long, status: PaymentStatus)
}
