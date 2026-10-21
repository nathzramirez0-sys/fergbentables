package com.blushdesk.app.domain.repository

import com.blushdesk.app.data.local.database.InventoryNotification
import com.blushdesk.app.data.local.database.InventorySummary
import com.blushdesk.app.data.local.database.MovementRow
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ProductSale
import com.blushdesk.app.domain.model.AdjustmentReason
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.InventoryReport
import com.blushdesk.app.domain.model.ProductReport
import com.blushdesk.app.domain.model.ReminderInterval
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The inventory: products, their stock, its history, and the inventory notifications.
 *
 * Stock only moves through the functions here (stock in, adjust) and through saving or deleting
 * orders in [ShowroomRepository]; each change is recorded as a stock movement. Saving functions
 * validate their input and throw [com.blushdesk.app.domain.model.UserFacingException] with a
 * readable message when it is wrong.
 */
interface InventoryRepository {
    /** Every product, archived ones included, by name. */
    val products: Flow<List<Product>>
    val summary: Flow<InventorySummary>
    val notifications: Flow<List<InventoryNotification>>
    val unreadNotifications: Flow<Int>

    fun recentMovements(limit: Int): Flow<List<MovementRow>>
    fun observeProduct(productId: Long): Flow<Product?>
    fun observeMovements(productId: Long): Flow<List<MovementRow>>
    fun observeSales(productId: Long): Flow<List<ProductSale>>

    /**
     * Inserts when [Product.id] is 0, with [initialStock] recorded as its first movement;
     * otherwise updates the product's details. The quantity is never changed by an edit.
     * Returns the product's id.
     */
    suspend fun saveProduct(product: Product, initialStock: Int = 0): Long

    /** A delivery: adds [quantity] units, dated [receivedAt]. */
    suspend fun stockIn(productId: Long, quantity: Int, receivedAt: Instant, supplier: String, reference: String, notes: String)

    /** A manual correction of [change] units (negative removes), with its reason. */
    suspend fun adjustStock(productId: Long, change: Int, reason: AdjustmentReason, notes: String)

    suspend fun setAvailability(productId: Long, availability: Availability)

    /** Archived products leave the active list and can't go on new orders; old orders keep them. */
    suspend fun setArchived(productId: Long, archived: Boolean)

    /** Permanently removes a product that no order uses. Refuses (archive instead) when one does. */
    suspend fun deleteProduct(productId: Long)

    /** Whether the product has ever been on an order: such a product can only be archived. */
    suspend fun hasSales(productId: Long): Boolean

    suspend fun setReminder(productId: Long, interval: ReminderInterval)

    suspend fun markNotificationsRead()
    suspend fun clearNotifications()

    /**
     * Records a "May stock pa" notification for every product whose reminder is due, and moves
     * each one's next reminder on. Safe to run any number of times: a reminder already recorded
     * for its slot is not recorded again. Returns how many were recorded.
     */
    suspend fun recordDueReminders(): Int

    /** Notifications not yet shown on the phone, oldest first. */
    suspend fun unpostedNotifications(): List<InventoryNotification>
    suspend fun markPosted(ids: List<Long>)

    suspend fun getInventoryReport(): InventoryReport
    suspend fun getProductReport(productId: Long): ProductReport
}
