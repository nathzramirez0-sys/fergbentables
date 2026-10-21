package com.blushdesk.app.data.repository

import com.blushdesk.app.data.local.database.InventoryDao
import com.blushdesk.app.data.local.database.InventoryNotification
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.StockMovement
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.NotificationKind
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.domain.model.isLowStock
import com.blushdesk.app.utils.Validation
import java.time.Clock
import java.time.Instant

/**
 * The only code that changes a product's quantity. Each change writes the new quantity and a
 * [StockMovement] (previous quantity, change, new quantity, who, when, why) together, so the
 * audit trail can never fall out of step with the stock.
 *
 * It must run inside a database transaction (the repositories call it from `withTransaction`):
 * a refused change throws, and the whole operation around it (an order, a delivery) rolls back.
 *
 * A decrease that takes a product to or below its minimum records one low-stock alert; the
 * product's lowStockAlerted flag stops a second alert until the stock has been back above the
 * minimum. Running out is the exception: the decrease that takes it to zero always alerts, even
 * after a low-stock alert in the same dip.
 */
class InventoryLedger(private val dao: InventoryDao, private val clock: Clock) {

    suspend fun record(
        productId: Long,
        change: Int,
        type: MovementType,
        performedBy: String,
        occurredAt: Instant = clock.instant(),
        orderId: Long? = null,
        reason: String = "",
        supplier: String = "",
        reference: String = "",
    ): StockMovement {
        val product = dao.getProduct(productId) ?: throw UserFacingException("That product no longer exists")
        val newQuantity = product.quantity + change
        if (newQuantity < 0) throw UserFacingException(insufficientStock(product))
        if (newQuantity > Validation.MAX_STOCK) {
            throw UserFacingException("${product.name} can hold at most ${Validation.MAX_STOCK} ${product.unit}")
        }

        val now = clock.instant()
        val low = isLowStock(newQuantity, product.minimumStock)
        val ranOut = newQuantity == 0 && product.quantity > 0
        val alert = change < 0 && low && (!product.lowStockAlerted || ranOut) && !product.archived &&
            product.availability != Availability.DISCONTINUED
        dao.setQuantity(productId, newQuantity, lowStockAlerted = low && (product.lowStockAlerted || alert), updatedAt = now)

        val movement = StockMovement(
            productId = productId,
            type = type,
            previousQuantity = product.quantity,
            change = change,
            newQuantity = newQuantity,
            orderId = orderId,
            reason = reason.trim(),
            supplier = supplier.trim(),
            reference = reference.trim(),
            performedBy = performedBy,
            occurredAt = occurredAt,
            createdAt = now,
        )
        val id = dao.insertMovement(movement)
        if (alert) dao.insertNotification(lowStockAlert(product, newQuantity, id, now))
        return movement.copy(id = id)
    }

    private fun lowStockAlert(product: Product, quantity: Int, movementId: Long, now: Instant) = InventoryNotification(
        productId = product.id,
        kind = NotificationKind.LOW_STOCK,
        title = if (quantity == 0) "Out of stock: ${product.name}" else "Low stock: ${product.name}",
        message = if (quantity == 0) {
            "${product.name} is out of stock."
        } else {
            "${product.name} is low in stock. Only $quantity ${product.unit} ${if (quantity == 1) "remains" else "remain"} " +
                "(minimum ${product.minimumStock})."
        },
        dedupKey = "LOW:${product.id}:$movementId",
        createdAt = now,
    )

    companion object {
        /** Who a movement made by the app itself (an order's sale or its reversal) is credited to. */
        const val SYSTEM = "System"

        fun insufficientStock(product: Product): String = when (product.quantity) {
            0 -> "Insufficient stock. ${product.name} is out of stock."
            1 -> "Insufficient stock. Only 1 ${product.unit} of ${product.name} is available."
            else -> "Insufficient stock. Only ${product.quantity} ${product.unit} of ${product.name} are available."
        }
    }
}
