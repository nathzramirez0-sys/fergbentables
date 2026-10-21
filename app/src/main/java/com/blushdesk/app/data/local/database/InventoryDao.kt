package com.blushdesk.app.data.local.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.ReminderInterval
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant

/** A stock movement with its product's name, code and unit, for history lists and exports. */
data class MovementRow(
    @Embedded val movement: StockMovement,
    val productName: String,
    val productSku: String?,
    val productUnit: String,
)

/** One sale of a product: the order line with its order and buyer. */
data class ProductSale(
    val orderId: Long,
    val buyerId: Long,
    val buyerName: String,
    val purchaseDateTime: Instant,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val lineTotal: BigDecimal,
    val paymentStatus: PaymentStatus,
    val fulfillmentStatus: FulfillmentStatus,
)

/**
 * Inventory-wide figures, counted in SQL. Only products that are not archived count towards the
 * stock figures; the status columns follow [com.blushdesk.app.domain.model.stockStatusOf].
 */
data class InventorySummary(
    val totalProducts: Int,
    val totalUnits: Int,
    val inStock: Int,
    val lowStock: Int,
    val outOfStock: Int,
    val unavailable: Int,
    val archived: Int,
    /** Selling price x quantity, over products not archived. */
    val stockValue: BigDecimal,
    /** Cost price x quantity, over products not archived that have a cost price. */
    val costValue: BigDecimal,
    /** Units taken by orders, minus units returned from edited or deleted orders. */
    val unitsSold: Int,
    val unitsRestocked: Int,
)

/**
 * Inventory queries. Stock quantities are written only through [setQuantity] together with a
 * [StockMovement] (see InventoryLedger), never by [updateProduct] on its own.
 */
@Dao
interface InventoryDao {

    // ---- Products ---------------------------------------------------------------------------

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun getProducts(): List<Product>

    @Query("SELECT * FROM products WHERE id = :productId")
    fun observeProduct(productId: Long): Flow<Product?>

    @Query("SELECT * FROM products WHERE id = :productId")
    suspend fun getProduct(productId: Long): Product?

    @Insert
    suspend fun insertProduct(product: Product): Long

    @Update
    suspend fun updateProduct(product: Product)

    /** Its movements and notifications go too (CASCADE). Refused by SQLite while an order line uses it. */
    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteProduct(productId: Long)

    @Query("SELECT COUNT(*) FROM products WHERE sku = :sku AND id <> :exceptId")
    suspend fun countSku(sku: String, exceptId: Long): Int

    @Query("SELECT COUNT(*) FROM order_items WHERE productId = :productId")
    suspend fun countOrderLines(productId: Long): Int

    @Query("UPDATE products SET quantity = :quantity, lowStockAlerted = :lowStockAlerted, updatedAt = :updatedAt WHERE id = :productId")
    suspend fun setQuantity(productId: Long, quantity: Int, lowStockAlerted: Boolean, updatedAt: Instant)

    // ---- Movements --------------------------------------------------------------------------

    @Insert
    suspend fun insertMovement(movement: StockMovement): Long

    @Query(
        """
        SELECT m.*, p.name AS productName, p.sku AS productSku, p.unit AS productUnit
        FROM stock_movements AS m JOIN products AS p ON p.id = m.productId
        ORDER BY m.occurredAt DESC, m.id DESC
        LIMIT :limit
        """,
    )
    fun observeRecentMovements(limit: Int): Flow<List<MovementRow>>

    @Query(
        """
        SELECT m.*, p.name AS productName, p.sku AS productSku, p.unit AS productUnit
        FROM stock_movements AS m JOIN products AS p ON p.id = m.productId
        WHERE m.productId = :productId
        ORDER BY m.occurredAt DESC, m.id DESC
        """,
    )
    fun observeMovementsForProduct(productId: Long): Flow<List<MovementRow>>

    /** Every movement, oldest first, for the export. */
    @Query(
        """
        SELECT m.*, p.name AS productName, p.sku AS productSku, p.unit AS productUnit
        FROM stock_movements AS m JOIN products AS p ON p.id = m.productId
        ORDER BY m.occurredAt ASC, m.id ASC
        """,
    )
    suspend fun getMovements(): List<MovementRow>

    @Query(
        """
        SELECT m.*, p.name AS productName, p.sku AS productSku, p.unit AS productUnit
        FROM stock_movements AS m JOIN products AS p ON p.id = m.productId
        WHERE m.productId = :productId
        ORDER BY m.occurredAt ASC, m.id ASC
        """,
    )
    suspend fun getMovementsForProduct(productId: Long): List<MovementRow>

    // ---- Sales of a product -----------------------------------------------------------------

    @Query(
        """
        SELECT o.id AS orderId, b.id AS buyerId, b.fullName AS buyerName, o.purchaseDateTime AS purchaseDateTime,
               i.quantity AS quantity, i.unitPrice AS unitPrice, i.lineTotal AS lineTotal,
               o.paymentStatus AS paymentStatus, o.fulfillmentStatus AS fulfillmentStatus
        FROM order_items AS i
        JOIN orders AS o ON o.id = i.orderId
        JOIN buyers AS b ON b.id = o.buyerId
        WHERE i.productId = :productId
        ORDER BY o.purchaseDateTime DESC, o.id DESC, i.position ASC
        """,
    )
    fun observeSales(productId: Long): Flow<List<ProductSale>>

    @Query(
        """
        SELECT o.id AS orderId, b.id AS buyerId, b.fullName AS buyerName, o.purchaseDateTime AS purchaseDateTime,
               i.quantity AS quantity, i.unitPrice AS unitPrice, i.lineTotal AS lineTotal,
               o.paymentStatus AS paymentStatus, o.fulfillmentStatus AS fulfillmentStatus
        FROM order_items AS i
        JOIN orders AS o ON o.id = i.orderId
        JOIN buyers AS b ON b.id = o.buyerId
        WHERE i.productId = :productId
        ORDER BY o.purchaseDateTime ASC, o.id ASC, i.position ASC
        """,
    )
    suspend fun getSales(productId: Long): List<ProductSale>

    // ---- Figures ----------------------------------------------------------------------------

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM products WHERE archived = 0) AS totalProducts,
          (SELECT COALESCE(SUM(quantity), 0) FROM products WHERE archived = 0) AS totalUnits,
          (SELECT COUNT(*) FROM products WHERE archived = 0 AND availability = 'AVAILABLE' AND quantity > minimumStock) AS inStock,
          (SELECT COUNT(*) FROM products WHERE archived = 0 AND availability = 'AVAILABLE' AND quantity > 0 AND quantity <= minimumStock) AS lowStock,
          (SELECT COUNT(*) FROM products WHERE archived = 0 AND availability = 'AVAILABLE' AND quantity <= 0) AS outOfStock,
          (SELECT COUNT(*) FROM products WHERE archived = 0 AND availability <> 'AVAILABLE') AS unavailable,
          (SELECT COUNT(*) FROM products WHERE archived = 1) AS archived,
          (SELECT COALESCE(SUM(quantity * sellingPrice), 0) FROM products WHERE archived = 0) AS stockValue,
          (SELECT COALESCE(SUM(quantity * costPrice), 0) FROM products WHERE archived = 0 AND costPrice IS NOT NULL) AS costValue,
          (SELECT COALESCE(SUM(-change), 0) FROM stock_movements WHERE type IN ('SALE', 'SALE_RETURN')) AS unitsSold,
          (SELECT COALESCE(SUM(change), 0) FROM stock_movements WHERE type = 'STOCK_IN') AS unitsRestocked
        """,
    )
    fun observeSummary(): Flow<InventorySummary>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM products WHERE archived = 0) AS totalProducts,
          (SELECT COALESCE(SUM(quantity), 0) FROM products WHERE archived = 0) AS totalUnits,
          (SELECT COUNT(*) FROM products WHERE archived = 0 AND availability = 'AVAILABLE' AND quantity > minimumStock) AS inStock,
          (SELECT COUNT(*) FROM products WHERE archived = 0 AND availability = 'AVAILABLE' AND quantity > 0 AND quantity <= minimumStock) AS lowStock,
          (SELECT COUNT(*) FROM products WHERE archived = 0 AND availability = 'AVAILABLE' AND quantity <= 0) AS outOfStock,
          (SELECT COUNT(*) FROM products WHERE archived = 0 AND availability <> 'AVAILABLE') AS unavailable,
          (SELECT COUNT(*) FROM products WHERE archived = 1) AS archived,
          (SELECT COALESCE(SUM(quantity * sellingPrice), 0) FROM products WHERE archived = 0) AS stockValue,
          (SELECT COALESCE(SUM(quantity * costPrice), 0) FROM products WHERE archived = 0 AND costPrice IS NOT NULL) AS costValue,
          (SELECT COALESCE(SUM(-change), 0) FROM stock_movements WHERE type IN ('SALE', 'SALE_RETURN')) AS unitsSold,
          (SELECT COALESCE(SUM(change), 0) FROM stock_movements WHERE type = 'STOCK_IN') AS unitsRestocked
        """,
    )
    suspend fun getSummary(): InventorySummary

    // ---- Reminders --------------------------------------------------------------------------

    /**
     * Products whose "May stock pa" reminder is due: switched on, still carried, not archived,
     * with stock left, and past their scheduled time.
     */
    @Query(
        """
        SELECT * FROM products
        WHERE reminderInterval <> 'NEVER' AND archived = 0 AND availability <> 'DISCONTINUED'
          AND quantity > 0 AND reminderNextAt IS NOT NULL AND reminderNextAt <= :now
        ORDER BY reminderNextAt ASC, id ASC
        """,
    )
    suspend fun getDueReminders(now: Instant): List<Product>

    @Query(
        "UPDATE products SET reminderInterval = :interval, reminderLastSentAt = :lastSentAt, " +
            "reminderNextAt = :nextAt WHERE id = :productId",
    )
    suspend fun setReminder(productId: Long, interval: ReminderInterval, lastSentAt: Instant?, nextAt: Instant?)

    // ---- Notifications ----------------------------------------------------------------------

    /** Returns -1 when one with the same dedupKey already exists: recording twice is a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotification(notification: InventoryNotification): Long

    @Query("SELECT * FROM inventory_notifications ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeNotifications(limit: Int): Flow<List<InventoryNotification>>

    @Query("SELECT COUNT(*) FROM inventory_notifications WHERE readAt IS NULL")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE inventory_notifications SET readAt = :now WHERE readAt IS NULL")
    suspend fun markAllRead(now: Instant)

    @Query("DELETE FROM inventory_notifications")
    suspend fun clearNotifications()

    @Query("SELECT * FROM inventory_notifications WHERE postedAt IS NULL ORDER BY createdAt ASC, id ASC")
    suspend fun getUnposted(): List<InventoryNotification>

    @Query("UPDATE inventory_notifications SET postedAt = :now WHERE id IN (:ids)")
    suspend fun markPosted(ids: List<Long>, now: Instant)
}
