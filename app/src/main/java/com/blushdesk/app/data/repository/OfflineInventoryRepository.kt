package com.blushdesk.app.data.repository

import androidx.room.withTransaction
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.local.database.InventoryNotification
import com.blushdesk.app.data.local.database.InventorySummary
import com.blushdesk.app.data.local.database.MovementRow
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ProductSale
import com.blushdesk.app.domain.model.AdjustmentReason
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.InventoryReport
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.NotificationKind
import com.blushdesk.app.domain.model.ProductReport
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.domain.model.isLowStock
import com.blushdesk.app.domain.repository.InventoryRepository
import com.blushdesk.app.utils.PhotoStore
import com.blushdesk.app.utils.Validation
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [InventoryRepository] backed by the local Room database.
 *
 * Every stock change goes through [InventoryLedger] inside a transaction, so the quantity, its
 * movement record and any low-stock alert are written together or not at all. Manual changes are
 * credited to the operator by name; order-driven ones (see OfflineShowroomRepository) to
 * [InventoryLedger.SYSTEM]. Reminder times are worked out in the device's time zone ([zone]).
 */
class OfflineInventoryRepository(
    private val database: AppDatabase,
    private val photos: PhotoStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : InventoryRepository {

    private val dao = database.inventoryDao()
    private val showroom = database.dao()
    private val ledger = InventoryLedger(dao, clock)

    override val products: Flow<List<Product>> = dao.observeProducts()
    override val summary: Flow<InventorySummary> = dao.observeSummary()
    override val notifications: Flow<List<InventoryNotification>> = dao.observeNotifications(NOTIFICATION_HISTORY)
    override val unreadNotifications: Flow<Int> = dao.observeUnreadCount()

    override fun recentMovements(limit: Int): Flow<List<MovementRow>> = dao.observeRecentMovements(limit)
    override fun observeProduct(productId: Long): Flow<Product?> = dao.observeProduct(productId)
    override fun observeMovements(productId: Long): Flow<List<MovementRow>> = dao.observeMovementsForProduct(productId)
    override fun observeSales(productId: Long): Flow<List<ProductSale>> = dao.observeSales(productId)

    override suspend fun saveProduct(product: Product, initialStock: Int): Long {
        val clean = product.copy(
            name = product.name.trim(),
            sku = Validation.normalizeSku(product.sku),
            category = product.category.trim(),
            description = product.description.trim(),
            unit = product.unit.trim(),
        )
        val problem = Validation.productProblem(
            clean.name, clean.sku, clean.category, clean.unit, clean.description,
            clean.sellingPrice, clean.costPrice, clean.minimumStock,
        ) ?: if (clean.id == 0L) Validation.stockCount(initialStock, "Initial stock") else null
        if (problem != null) throw UserFacingException(problem)

        val now = clock.instant()
        var replacedImage: String? = null
        val id = database.withTransaction {
            clean.sku?.let { sku ->
                if (dao.countSku(sku, clean.id) > 0) throw UserFacingException("Another product already uses the code $sku")
            }
            if (clean.id == 0L) {
                val id = dao.insertProduct(
                    clean.copy(
                        quantity = 0,
                        archived = false,
                        reminderInterval = ReminderInterval.NEVER,
                        reminderLastSentAt = null,
                        reminderNextAt = null,
                        lowStockAlerted = false,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                // The opening count is the product's first movement, even when it is zero.
                ledger.record(id, initialStock, MovementType.INITIAL, performedBy = operatorName(), reason = "Product added")
                id
            } else {
                val previous = dao.getProduct(clean.id) ?: throw UserFacingException("That product no longer exists")
                dao.updateProduct(
                    clean.copy(
                        quantity = previous.quantity,
                        archived = previous.archived,
                        reminderInterval = previous.reminderInterval,
                        reminderLastSentAt = previous.reminderLastSentAt,
                        reminderNextAt = previous.reminderNextAt,
                        // A raised minimum does not alert by itself; a lowered one can end the alert.
                        lowStockAlerted = previous.lowStockAlerted && isLowStock(previous.quantity, clean.minimumStock),
                        createdAt = previous.createdAt,
                        updatedAt = now,
                    ),
                )
                if (previous.imageUri != clean.imageUri) replacedImage = previous.imageUri
                clean.id
            }
        }
        photos.delete(replacedImage)
        return id
    }

    override suspend fun stockIn(productId: Long, quantity: Int, receivedAt: Instant, supplier: String, reference: String, notes: String) {
        val problem = Validation.stockIn(quantity.toString())
            ?: Validation.note(supplier, Validation.MAX_SUPPLIER)
            ?: Validation.note(reference, Validation.MAX_REFERENCE)
            ?: Validation.note(notes)
            ?: if (receivedAt.isAfter(clock.instant().plusSeconds(FUTURE_SLACK_SECONDS))) "The date can't be in the future" else null
        if (problem != null) throw UserFacingException(problem)

        database.withTransaction {
            val product = activeProduct(productId)
            ledger.record(
                productId = product.id,
                change = quantity,
                type = MovementType.STOCK_IN,
                performedBy = operatorName(),
                occurredAt = receivedAt,
                reason = notes,
                supplier = supplier,
                reference = reference,
            )
        }
    }

    override suspend fun adjustStock(productId: Long, change: Int, reason: AdjustmentReason, notes: String) {
        val problem = Validation.adjustment(change)
            ?: Validation.note(notes)
            ?: when {
                reason.adds == true && change < 0 -> "\"${reason.label}\" adds stock; enter a positive number"
                reason.adds == false && change > 0 -> "\"${reason.label}\" removes stock; enter a negative number"
                reason == AdjustmentReason.OTHER && notes.isBlank() -> "Say what the adjustment is for"
                else -> null
            }
        if (problem != null) throw UserFacingException(problem)

        database.withTransaction {
            val product = activeProduct(productId)
            ledger.record(
                productId = product.id,
                change = change,
                type = MovementType.ADJUSTMENT,
                performedBy = operatorName(),
                reason = if (notes.isBlank()) reason.label else "${reason.label}: ${notes.trim()}",
            )
        }
    }

    override suspend fun setAvailability(productId: Long, availability: Availability) {
        database.withTransaction {
            val product = dao.getProduct(productId) ?: throw UserFacingException("That product no longer exists")
            if (product.archived) throw UserFacingException("Restore ${product.name} from the archive first")
            dao.updateProduct(product.copy(availability = availability, updatedAt = clock.instant()))
        }
    }

    override suspend fun setArchived(productId: Long, archived: Boolean) {
        database.withTransaction {
            val product = dao.getProduct(productId) ?: throw UserFacingException("That product no longer exists")
            dao.updateProduct(product.copy(archived = archived, updatedAt = clock.instant()))
        }
    }

    override suspend fun deleteProduct(productId: Long) {
        var image: String? = null
        database.withTransaction {
            val product = dao.getProduct(productId) ?: return@withTransaction
            val lines = dao.countOrderLines(productId)
            if (lines > 0) {
                throw UserFacingException(
                    "${product.name} is on $lines order line${if (lines == 1) "" else "s"}, so it can't be deleted. " +
                        "Archive it instead: it disappears from new orders and old orders keep it.",
                )
            }
            dao.deleteProduct(productId)
            image = product.imageUri
        }
        photos.delete(image)
    }

    override suspend fun hasSales(productId: Long): Boolean = dao.countOrderLines(productId) > 0

    override suspend fun setReminder(productId: Long, interval: ReminderInterval) {
        database.withTransaction {
            val product = dao.getProduct(productId) ?: throw UserFacingException("That product no longer exists")
            // Counted from the last reminder, so changing "every 3 days" to "every week" keeps the
            // rhythm; a product never reminded about starts counting now.
            val next = interval.next(product.reminderLastSentAt ?: clock.instant(), zone)
            dao.setReminder(productId, interval, product.reminderLastSentAt, next)
        }
    }

    override suspend fun markNotificationsRead() = dao.markAllRead(clock.instant())

    override suspend fun clearNotifications() = dao.clearNotifications()

    override suspend fun recordDueReminders(): Int {
        val now = clock.instant()
        var recorded = 0
        for (due in dao.getDueReminders(now)) {
            database.withTransaction {
                // Read again inside the transaction: settings may have changed since the query.
                val product = dao.getProduct(due.id) ?: return@withTransaction
                val slot = product.reminderNextAt ?: return@withTransaction
                if (slot.isAfter(now) || product.reminderInterval == ReminderInterval.NEVER || product.quantity <= 0 ||
                    product.archived || product.availability == Availability.DISCONTINUED
                ) {
                    return@withTransaction
                }
                val id = dao.insertNotification(
                    InventoryNotification(
                        productId = product.id,
                        kind = NotificationKind.REMAINING_STOCK,
                        title = "May stock pa: ${product.name}",
                        message = "May stock pa ng ${product.name}: ${product.quantity} ${product.unit} left. " +
                            "There is still stock available for this product.",
                        // One per scheduled slot: a second run for the same slot inserts nothing.
                        dedupKey = "REMIND:${product.id}:${slot.toEpochMilli()}",
                        createdAt = now,
                    ),
                )
                val sent = id != -1L
                dao.setReminder(
                    productId = product.id,
                    interval = product.reminderInterval,
                    lastSentAt = if (sent) now else product.reminderLastSentAt,
                    nextAt = product.reminderInterval.next(now, zone),
                )
                if (sent) recorded++
            }
        }
        return recorded
    }

    override suspend fun unpostedNotifications(): List<InventoryNotification> = dao.getUnposted()

    override suspend fun markPosted(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.markPosted(ids, clock.instant())
    }

    override suspend fun getInventoryReport(): InventoryReport = database.withTransaction {
        InventoryReport(
            operator = showroom.getOperator() ?: OperatorProfile(),
            products = dao.getProducts(),
            movements = dao.getMovements(),
            summary = dao.getSummary(),
            takenAt = clock.instant(),
        )
    }

    override suspend fun getProductReport(productId: Long): ProductReport = database.withTransaction {
        val product = dao.getProduct(productId) ?: throw UserFacingException("That product no longer exists")
        ProductReport(
            operator = showroom.getOperator() ?: OperatorProfile(),
            product = product,
            movements = dao.getMovementsForProduct(productId),
            sales = dao.getSales(productId),
            takenAt = clock.instant(),
        )
    }

    private suspend fun activeProduct(productId: Long): Product {
        val product = dao.getProduct(productId) ?: throw UserFacingException("That product no longer exists")
        if (product.archived) throw UserFacingException("Restore ${product.name} from the archive before changing its stock")
        return product
    }

    /** Who manual stock changes are credited to: the operator's name from their profile. */
    private suspend fun operatorName(): String =
        showroom.getOperator()?.fullName?.trim().orEmpty().ifEmpty { "Operator" }

    private companion object {
        const val NOTIFICATION_HISTORY = 200

        /** A delivery dated a minute ahead is a clock difference, not a future date. */
        const val FUTURE_SLACK_SECONDS = 60L
    }
}
