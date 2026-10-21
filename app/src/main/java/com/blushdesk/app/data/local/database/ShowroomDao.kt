package com.blushdesk.app.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Every query the app runs. The SQL names enum values ('PAID', 'DELIVERED', ...) because
 * [Converters] stores enums by name; renaming an enum constant therefore needs a migration.
 * Money columns hold centavos, so SUM() results are exact and map back to BigDecimal.
 */
@Dao
interface ShowroomDao {

    // ---- Operator ---------------------------------------------------------------------------

    @Query("SELECT * FROM operator_profile WHERE id = ${OperatorProfile.ACTIVE_ID}")
    fun observeOperator(): Flow<OperatorProfile?>

    @Query("SELECT * FROM operator_profile WHERE id = ${OperatorProfile.ACTIVE_ID}")
    suspend fun getOperator(): OperatorProfile?

    @Upsert
    suspend fun upsertOperator(profile: OperatorProfile)

    // ---- Buyers: CRUD -----------------------------------------------------------------------

    @Insert
    suspend fun insertBuyer(buyer: Buyer): Long

    @Update
    suspend fun updateBuyer(buyer: Buyer)

    /** The buyer's orders go too, through the foreign key's ON DELETE CASCADE. */
    @Query("DELETE FROM buyers WHERE id = :buyerId")
    suspend fun deleteBuyer(buyerId: Long)

    @Query("SELECT * FROM buyers WHERE id = :buyerId")
    suspend fun getBuyer(buyerId: Long): Buyer?

    /** The selected buyer; emits null once they are deleted. */
    @Query("SELECT * FROM buyers WHERE id = :buyerId")
    fun observeBuyer(buyerId: Long): Flow<Buyer?>

    // ---- Buyers: list and search ------------------------------------------------------------

    /**
     * Buyers matching [pattern] (a ready-made LIKE pattern, see OfflineShowroomRepository) by name,
     * contact number, Facebook name or email, each with an order count and the state of their
     * latest order.
     */
    @Query(
        """
        SELECT b.*,
               (SELECT COUNT(*) FROM orders o WHERE o.buyerId = b.id) AS orderCount,
               (SELECT o.fulfillmentStatus FROM orders o WHERE o.buyerId = b.id
                    ORDER BY o.purchaseDateTime DESC, o.id DESC LIMIT 1) AS latestFulfillmentStatus,
               (SELECT o.paymentStatus FROM orders o WHERE o.buyerId = b.id
                    ORDER BY o.purchaseDateTime DESC, o.id DESC LIMIT 1) AS latestPaymentStatus
        FROM buyers AS b
        WHERE b.fullName LIKE :pattern ESCAPE '\'
           OR b.contactNumber LIKE :pattern ESCAPE '\'
           OR b.facebookName LIKE :pattern ESCAPE '\'
           OR b.email LIKE :pattern ESCAPE '\'
        ORDER BY b.fullName COLLATE NOCASE ASC, b.id ASC
        """,
    )
    fun searchBuyers(pattern: String): Flow<List<BuyerListItem>>

    // ---- Orders: CRUD -----------------------------------------------------------------------

    @Insert
    suspend fun insertOrder(order: Order): Long

    @Update
    suspend fun updateOrder(order: Order)

    /** The order's items go too, through ON DELETE CASCADE. */
    @Query("DELETE FROM orders WHERE id = :orderId")
    suspend fun deleteOrder(orderId: Long)

    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrder(orderId: Long): Order?

    @Transaction
    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrderWithItems(orderId: Long): OrderWithItems?

    /** Every order of one buyer with its items: what deleting the buyer has to return to stock. */
    @Transaction
    @Query("SELECT * FROM orders WHERE buyerId = :buyerId")
    suspend fun getOrdersForBuyer(buyerId: Long): List<OrderWithItems>

    /** One buyer with every order and item, for their profile export. */
    @Transaction
    @Query("SELECT * FROM buyers WHERE id = :buyerId")
    suspend fun getBuyerWithOrders(buyerId: Long): BuyerWithOrders?

    /** Order history of one buyer, newest purchase first, each with its items. */
    @Transaction
    @Query("SELECT * FROM orders WHERE buyerId = :buyerId ORDER BY purchaseDateTime DESC, id DESC")
    fun observeOrdersForBuyer(buyerId: Long): Flow<List<OrderWithItems>>

    // ---- Order items ------------------------------------------------------------------------

    @Insert
    suspend fun insertItems(items: List<OrderItem>)

    /** Used when an order is edited: its lines are replaced as a whole. */
    @Query("DELETE FROM order_items WHERE orderId = :orderId")
    suspend fun deleteItemsOfOrder(orderId: Long)

    @Query("UPDATE orders SET fulfillmentStatus = :status, updatedAt = :updatedAt WHERE id = :orderId")
    suspend fun updateFulfillmentStatus(orderId: Long, status: FulfillmentStatus, updatedAt: Instant)

    @Query("UPDATE orders SET paymentStatus = :status, updatedAt = :updatedAt WHERE id = :orderId")
    suspend fun updatePaymentStatus(orderId: Long, status: PaymentStatus, updatedAt: Instant)

    // ---- Orders by status -------------------------------------------------------------------

    /** For example every UNPAID order, oldest first: the collection list. */
    @Query("SELECT * FROM orders WHERE paymentStatus = :status ORDER BY purchaseDateTime ASC, id ASC")
    fun observeOrdersByPaymentStatus(status: PaymentStatus): Flow<List<Order>>

    /** For example every order still PREPARING, oldest first: the dispatch list. */
    @Query("SELECT * FROM orders WHERE fulfillmentStatus = :status ORDER BY purchaseDateTime ASC, id ASC")
    fun observeOrdersByFulfillmentStatus(status: FulfillmentStatus): Flow<List<Order>>

    // ---- Totals -----------------------------------------------------------------------------

    @Query(
        """
        SELECT COUNT(*) AS orderCount,
               COALESCE(SUM(CASE WHEN fulfillmentStatus <> 'DELIVERED' THEN 1 ELSE 0 END), 0) AS openOrders,
               COALESCE(SUM(CASE WHEN paymentStatus = 'PAID' THEN totalAmount ELSE 0 END), 0) AS paidAmount,
               COALESCE(SUM(CASE WHEN paymentStatus <> 'PAID' THEN totalAmount ELSE 0 END), 0) AS outstandingAmount,
               COALESCE(SUM(totalAmount), 0) AS totalAmount
        FROM orders
        WHERE buyerId = :buyerId
        """,
    )
    fun observeOrderTotals(buyerId: Long): Flow<OrderTotals>

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM buyers) AS buyerCount,
               (SELECT COUNT(*) FROM orders) AS orderCount,
               (SELECT COUNT(*) FROM orders WHERE fulfillmentStatus <> 'DELIVERED') AS openOrders,
               (SELECT COALESCE(SUM(totalAmount), 0) FROM orders WHERE paymentStatus = 'PAID') AS paidAmount,
               (SELECT COALESCE(SUM(totalAmount), 0) FROM orders WHERE paymentStatus <> 'PAID') AS outstandingAmount
        """,
    )
    fun observeShowroomSummary(): Flow<ShowroomSummary>

    // ---- Export -----------------------------------------------------------------------------

    /** Every buyer with every order and item, for the Buyers and Orders sheets. */
    @Transaction
    @Query("SELECT * FROM buyers ORDER BY fullName COLLATE NOCASE ASC, id ASC")
    suspend fun getAllBuyersWithOrders(): List<BuyerWithOrders>

    /** The figures of the Summary sheet: counts per payment and fulfillment status, total sales. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM buyers) AS totalBuyers,
               (SELECT COUNT(*) FROM orders) AS totalOrders,
               (SELECT COUNT(*) FROM orders WHERE paymentStatus = 'PAID') AS paidOrders,
               (SELECT COUNT(*) FROM orders WHERE paymentStatus = 'UNPAID') AS unpaidOrders,
               (SELECT COUNT(*) FROM orders WHERE paymentStatus = 'PENDING') AS pendingOrders,
               (SELECT COUNT(*) FROM orders WHERE fulfillmentStatus = 'PROCESSING') AS processingOrders,
               (SELECT COUNT(*) FROM orders WHERE fulfillmentStatus = 'PREPARING') AS preparingOrders,
               (SELECT COUNT(*) FROM orders WHERE fulfillmentStatus = 'DELIVERED') AS deliveredOrders,
               (SELECT COALESCE(SUM(totalAmount), 0) FROM orders) AS totalRecordedSales
        """,
    )
    suspend fun getExportSummary(): ExportSummary
}
