package com.blushdesk.app.data.repository

import androidx.room.withTransaction
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerListItem
import com.blushdesk.app.data.local.database.OpenOrder
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderTotals
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.data.local.database.Payment
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.domain.model.BuyerReport
import com.blushdesk.app.domain.model.ExportSnapshot
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.domain.repository.ShowroomRepository
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.Payments
import com.blushdesk.app.utils.PhotoStore
import com.blushdesk.app.utils.Validation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/**
 * [ShowroomRepository] backed by the local Room database: fully offline.
 *
 * This is where records are made consistent before they are stored: text is trimmed, every line
 * total (price x quantity) and the order total (sum of lines) are recomputed, createdAt / updatedAt
 * are stamped from [clock], and photos replaced by an edit are deleted once the new row is safely
 * written. An order and its lines are always written in one transaction.
 */
class OfflineShowroomRepository(
    private val database: AppDatabase,
    private val photos: PhotoStore,
    private val clock: Clock = Clock.systemUTC(),
) : ShowroomRepository {

    private val dao = database.dao()
    private val inventory = database.inventoryDao()
    private val ledger = InventoryLedger(inventory, clock)

    override val operator: Flow<OperatorProfile> = dao.observeOperator().map { it ?: OperatorProfile() }
    override val summary: Flow<ShowroomSummary> = dao.observeShowroomSummary()
    override val products: Flow<List<Product>> = inventory.observeProducts()

    override val openOrders: Flow<List<OpenOrder>> = dao.observeOpenOrders()

    override fun searchBuyers(query: String): Flow<List<BuyerListItem>> = dao.searchBuyers(likePattern(query))
    override fun observeBuyer(buyerId: Long): Flow<Buyer?> = dao.observeBuyer(buyerId)
    override fun observeOrders(buyerId: Long): Flow<List<OrderWithItems>> = dao.observeOrdersForBuyer(buyerId)
    override fun observeOrderTotals(buyerId: Long): Flow<OrderTotals> = dao.observeOrderTotals(buyerId)

    override suspend fun getOperator(): OperatorProfile = dao.getOperator() ?: OperatorProfile()
    override suspend fun getBuyer(buyerId: Long): Buyer? = dao.getBuyer(buyerId)
    override suspend fun getOrder(orderId: Long): OrderWithItems? = dao.getOrderWithItems(orderId)

    override suspend fun getExportSnapshot(): ExportSnapshot = database.withTransaction {
        ExportSnapshot(
            operator = getOperator(),
            buyers = dao.getAllBuyersWithOrders(),
            summary = dao.getExportSummary(),
            takenAt = clock.instant(),
            products = inventory.getProducts(),
            movements = inventory.getMovements(),
            inventory = inventory.getSummary(),
        )
    }

    override suspend fun getBuyerReport(buyerId: Long): BuyerReport = database.withTransaction {
        BuyerReport(
            operator = getOperator(),
            buyer = dao.getBuyerWithOrders(buyerId) ?: throw UserFacingException("That buyer no longer exists"),
            takenAt = clock.instant(),
        )
    }

    override suspend fun saveOperator(profile: OperatorProfile) {
        val problem = Validation.name(profile.fullName, "Your name")
            ?: Validation.name(profile.storeName, "Showroom name")
            ?: Validation.email(profile.email)
            ?: Validation.phone(profile.phoneNumber, required = false)
            ?: Validation.orderPrefix(profile.orderPrefix.trim().uppercase())
        if (problem != null) throw UserFacingException(problem)

        val now = clock.instant()
        val previous = dao.getOperator()
        dao.upsertOperator(
            profile.copy(
                id = OperatorProfile.ACTIVE_ID,
                fullName = profile.fullName.trim(),
                storeName = profile.storeName.trim(),
                email = profile.email.trim(),
                phoneNumber = profile.phoneNumber.trim(),
                orderPrefix = profile.orderPrefix.trim().uppercase(),
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        if (previous?.profileImageUri != profile.profileImageUri) photos.delete(previous?.profileImageUri)
    }

    override suspend fun saveBuyer(buyer: Buyer): Long {
        val clean = buyer.copy(
            fullName = buyer.fullName.trim(),
            contactNumber = buyer.contactNumber.trim(),
            facebookName = buyer.facebookName.trim(),
            email = buyer.email.trim(),
        )
        Validation.buyerProblem(clean)?.let { throw UserFacingException(it) }
        val now = clock.instant()

        if (clean.id == 0L) return dao.insertBuyer(clean.copy(createdAt = now, updatedAt = now))

        val previous = dao.getBuyer(clean.id) ?: throw UserFacingException("That buyer no longer exists")
        dao.updateBuyer(clean.copy(dateAdded = previous.dateAdded, createdAt = previous.createdAt, updatedAt = now))
        if (previous.profileImageUri != clean.profileImageUri) photos.delete(previous.profileImageUri)
        return clean.id
    }

    override suspend fun deleteBuyer(buyerId: Long, restock: Boolean) {
        val buyer = database.withTransaction {
            val buyer = dao.getBuyer(buyerId)
            if (restock) {
                val reason = "Buyer ${buyer?.fullName.orEmpty()} deleted"
                dao.getOrdersForBuyer(buyerId).forEach { returnToStock(it, reason) }
            }
            dao.deleteBuyer(buyerId)
            buyer
        }
        photos.delete(buyer?.profileImageUri)
    }

    override suspend fun saveOrder(order: Order, items: List<OrderItem>, paidNow: BigDecimal): Long {
        val lines = items.mapIndexed { index, item ->
            val name = item.productName.trim()
            item.copy(id = 0, position = index, productName = name, lineTotal = Money.total(item.unitPrice, item.quantity))
        }
        Validation.itemsProblem(lines)?.let { throw UserFacingException(it) }
        val total = lines.fold(Money.ZERO) { sum, line -> sum + line.lineTotal }
        if (order.id == 0L && paidNow.signum() != 0) Payments.amountProblem(paidNow, total)?.let { throw UserFacingException(it) }
        val now = clock.instant()

        return database.withTransaction {
            dao.getBuyer(order.buyerId) ?: throw UserFacingException("That buyer no longer exists")
            val previous = if (order.id == 0L) {
                null
            } else {
                dao.getOrderWithItems(order.id) ?: throw UserFacingException("That order no longer exists")
            }
            val orderId = if (previous == null) {
                val prefix = dao.getOperator()?.orderPrefix ?: OperatorProfile.DEFAULT_ORDER_PREFIX
                dao.insertOrder(
                    order.copy(
                        totalAmount = total,
                        paymentStatus = Payments.status(total, paidNow),
                        numberPrefix = prefix,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                dao.updateOrder(
                    order.copy(
                        totalAmount = total,
                        paymentStatus = Payments.status(total, previous.paidAmount),
                        numberPrefix = previous.order.numberPrefix,
                        createdAt = previous.order.createdAt,
                        updatedAt = now,
                    ),
                )
                dao.deleteItemsOfOrder(order.id)
                order.id
            }
            val number = dao.getOrder(orderId)?.let(Formats::orderNumber) ?: Formats.orderNumber(orderId)
            moveStock(orderId, number, before = previous?.itemRows.orEmpty(), after = lines, edited = previous != null)
            dao.insertItems(lines.map { it.copy(orderId = orderId) })
            if (previous == null && paidNow.signum() > 0) {
                dao.insertPayment(
                    Payment(orderId = orderId, amount = paidNow, paidAt = order.purchaseDateTime, paymentMode = order.paymentMode, createdAt = now),
                )
            }
            orderId
        }
    }

    override suspend fun deleteOrder(orderId: Long, restock: Boolean) {
        database.withTransaction {
            val order = dao.getOrderWithItems(orderId) ?: return@withTransaction
            if (restock) returnToStock(order, "Order ${Formats.orderNumber(order.order)} deleted")
            dao.deleteOrder(orderId)
        }
    }

    /**
     * Brings stock in line with an order's product lines going from [before] to [after]. Per
     * product only the difference moves: more units are taken (a sale), fewer are put back. So a
     * new order takes everything, an unchanged re-save takes nothing, and an edit takes or returns
     * just what changed. Runs inside the order's transaction, so a refusal undoes the whole save.
     */
    private suspend fun moveStock(orderId: Long, number: String, before: List<OrderItem>, after: List<OrderItem>, edited: Boolean) {
        val held = unitsPerProduct(before)
        val wanted = unitsPerProduct(after)
        for (productId in (held.keys + wanted.keys).sorted()) {
            val delta = (wanted[productId] ?: 0) - (held[productId] ?: 0)
            if (delta > 0) {
                val product = inventory.getProduct(productId) ?: throw UserFacingException("A product on this order no longer exists")
                if (!product.isSellable) {
                    throw UserFacingException("${product.name} is ${product.stockStatus.label.lowercase()} and can't be added to an order")
                }
                ledger.record(
                    productId = productId,
                    change = -delta,
                    type = MovementType.SALE,
                    performedBy = InventoryLedger.SYSTEM,
                    orderId = orderId,
                    reason = if (edited) "Order $number edited" else "Order $number",
                )
            } else if (delta < 0) {
                ledger.record(
                    productId = productId,
                    change = -delta,
                    type = MovementType.SALE_RETURN,
                    performedBy = InventoryLedger.SYSTEM,
                    orderId = orderId,
                    reason = "Order $number edited",
                )
            }
        }
    }

    /** Puts every inventory item of [order] back in stock. Runs inside the caller's transaction. */
    private suspend fun returnToStock(order: OrderWithItems, reason: String) {
        unitsPerProduct(order.itemRows).toSortedMap().forEach { (productId, units) ->
            ledger.record(
                productId = productId,
                change = units,
                type = MovementType.SALE_RETURN,
                performedBy = InventoryLedger.SYSTEM,
                orderId = order.order.id,
                reason = reason,
            )
        }
    }

    private fun unitsPerProduct(lines: List<OrderItem>): Map<Long, Int> = lines
        .mapNotNull { line -> line.productId?.let { it to line.quantity } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, quantities) -> quantities.sum() }

    override suspend fun setFulfillmentStatus(orderId: Long, status: FulfillmentStatus) =
        dao.updateFulfillmentStatus(orderId, status, clock.instant())

    override suspend fun recordPayment(orderId: Long, amount: BigDecimal, mode: PaymentMode, paidAt: Instant, note: String): Long =
        database.withTransaction {
            val order = dao.getOrderWithItems(orderId) ?: throw UserFacingException("That order no longer exists")
            Payments.amountProblem(amount, order.balance)?.let { throw UserFacingException(it) }
            val id = dao.insertPayment(
                Payment(orderId = orderId, amount = amount, paidAt = paidAt, paymentMode = mode, note = note.trim(), createdAt = clock.instant()),
            )
            refreshPaymentStatus(orderId)
            id
        }

    override suspend fun markPaid(orderId: Long): Long? = database.withTransaction {
        val order = dao.getOrderWithItems(orderId) ?: throw UserFacingException("That order no longer exists")
        if (order.balance.signum() == 0) {
            refreshPaymentStatus(orderId)
            return@withTransaction null
        }
        val now = clock.instant()
        val id = dao.insertPayment(
            Payment(orderId = orderId, amount = order.balance, paidAt = now, paymentMode = order.order.paymentMode, createdAt = now),
        )
        refreshPaymentStatus(orderId)
        id
    }

    override suspend fun deletePayment(paymentId: Long) {
        database.withTransaction {
            val payment = dao.getPayment(paymentId) ?: return@withTransaction
            dao.deletePayment(paymentId)
            refreshPaymentStatus(payment.orderId)
        }
    }

    override suspend fun setDelivery(orderId: Long, address: String, date: Instant?) =
        dao.updateDelivery(orderId, address.trim(), date, clock.instant())

    /** Sets the order's payment status from its total and payments. Runs inside the caller's transaction. */
    private suspend fun refreshPaymentStatus(orderId: Long) {
        val order = dao.getOrderWithItems(orderId) ?: return
        val status = Payments.status(order.order.totalAmount, order.paidAmount)
        if (status != order.order.paymentStatus) dao.updatePaymentStatus(orderId, status, clock.instant())
    }

    internal companion object {
        /**
         * Wraps the user's text in `%...%` for SQL LIKE. `%` and `_` are wildcards in LIKE, so a
         * search for "50%" would match everything; they are escaped with a backslash, which the
         * query declares with `ESCAPE '\'`.
         */
        fun likePattern(query: String): String {
            val escaped = query.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            return "%$escaped%"
        }
    }
}
