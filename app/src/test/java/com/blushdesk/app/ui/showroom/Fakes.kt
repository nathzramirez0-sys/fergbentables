package com.blushdesk.app.ui.showroom

import android.net.Uri
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerListItem
import com.blushdesk.app.data.local.database.BuyerWithOrders
import com.blushdesk.app.data.local.database.ExportSummary
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
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.domain.repository.ShowroomRepository
import com.blushdesk.app.utils.DocumentService
import com.blushdesk.app.utils.ExportDocument
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.Payments
import com.blushdesk.app.utils.PhotoStore
import com.blushdesk.app.utils.ReceiptDocument
import com.blushdesk.app.utils.Validation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import java.math.BigDecimal
import java.time.Instant

/** An in-memory [ShowroomRepository] that behaves like the Room one for what the tests need. */
class FakeRepository : ShowroomRepository {
    private val operatorState = MutableStateFlow(OperatorProfile())
    private val buyers = MutableStateFlow<List<Buyer>>(emptyList())
    private val orders = MutableStateFlow<List<Order>>(emptyList())
    private val items = MutableStateFlow<List<OrderItem>>(emptyList())
    private val payments = MutableStateFlow<List<Payment>>(emptyList())
    private var nextPaymentId = 1L
    private var nextBuyerId = 1L
    private var nextOrderId = 1L
    private var nextItemId = 1L

    /** When set, every save throws this instead of saving (to test error reporting). */
    var failSavesWith: Exception? = null

    /** The inventory the order form offers; stock itself is covered by the Room repository tests. */
    val productState = MutableStateFlow<List<Product>>(emptyList())

    /** What the last deleteOrder / deleteBuyer was told about returning items to stock. */
    var lastRestock: Boolean? = null

    private fun withItems(order: Order) =
        OrderWithItems(order, items.value.filter { it.orderId == order.id }, payments.value.filter { it.orderId == order.id })

    override val operator: Flow<OperatorProfile> = operatorState

    override val summary: Flow<ShowroomSummary> = combine(buyers, orders, payments) { b, o, _ ->
        ShowroomSummary(
            buyerCount = b.size,
            orderCount = o.size,
            openOrders = o.count { it.fulfillmentStatus != FulfillmentStatus.DELIVERED },
            paidAmount = o.paidSum(),
            outstandingAmount = o.balanceSum(),
        )
    }

    override fun searchBuyers(query: String): Flow<List<BuyerListItem>> = combine(buyers, orders, payments) { b, o, _ ->
        val q = query.trim().lowercase()
        b.filter { q.isEmpty() || it.fullName.lowercase().contains(q) || it.contactNumber.contains(q) || it.facebookName.lowercase().contains(q) || it.email.lowercase().contains(q) }
            .sortedBy { it.fullName.lowercase() }
            .map { buyer ->
                val mine = o.filter { it.buyerId == buyer.id }.sortedByDescending { it.purchaseDateTime }
                BuyerListItem(
                    buyer, mine.size, mine.firstOrNull()?.fulfillmentStatus, mine.firstOrNull()?.paymentStatus,
                    openOrders = mine.count { it.fulfillmentStatus != FulfillmentStatus.DELIVERED },
                    balance = mine.balanceSum(),
                )
            }
    }

    override val products: Flow<List<Product>> = productState

    override val openOrders: Flow<List<OpenOrder>> = combine(orders, buyers, items) { o, b, lines ->
        o.filter { it.fulfillmentStatus != FulfillmentStatus.DELIVERED }
            .sortedWith(compareBy<Order>({ it.deliveryDate ?: it.purchaseDateTime }, { it.id }))
            .map { order ->
                val mine = lines.filter { it.orderId == order.id }.sortedBy { it.position }
                OpenOrder(order, b.firstOrNull { it.id == order.buyerId }?.fullName.orEmpty(), mine.firstOrNull()?.productName, mine.size)
            }
    }

    override fun observeBuyer(buyerId: Long): Flow<Buyer?> = buyers.map { list -> list.firstOrNull { it.id == buyerId } }

    override fun observeOrders(buyerId: Long): Flow<List<OrderWithItems>> = combine(orders, items, payments) { list, _, _ ->
        list.filter { it.buyerId == buyerId }
            .sortedWith(compareByDescending<Order> { it.purchaseDateTime }.thenByDescending { it.id })
            .map(::withItems)
    }

    override fun observeOrderTotals(buyerId: Long): Flow<OrderTotals> = combine(orders, payments) { list, _ ->
        val mine = list.filter { it.buyerId == buyerId }
        OrderTotals(
            orderCount = mine.size,
            openOrders = mine.count { it.fulfillmentStatus != FulfillmentStatus.DELIVERED },
            paidAmount = mine.paidSum(),
            outstandingAmount = mine.balanceSum(),
            totalAmount = mine.sum(),
        )
    }

    override suspend fun getOperator() = operatorState.value
    override suspend fun getBuyer(buyerId: Long) = buyers.value.firstOrNull { it.id == buyerId }
    override suspend fun getOrder(orderId: Long) = orders.value.firstOrNull { it.id == orderId }?.let(::withItems)

    override suspend fun getExportSnapshot() = ExportSnapshot(
        operator = operatorState.value,
        buyers = buyers.value.map { b -> BuyerWithOrders(b, orders.value.filter { it.buyerId == b.id }.map(::withItems)) },
        summary = ExportSummary(buyers.value.size, orders.value.size, 0, 0, 0, 0, 0, 0, orders.value.sum()),
        takenAt = Instant.EPOCH,
    )

    override suspend fun getBuyerReport(buyerId: Long) = BuyerReport(
        operator = operatorState.value,
        buyer = BuyerWithOrders(getBuyer(buyerId)!!, orders.value.filter { it.buyerId == buyerId }.map(::withItems)),
        takenAt = Instant.EPOCH,
    )

    override suspend fun saveOperator(profile: OperatorProfile) {
        failSavesWith?.let { throw it }
        operatorState.value = profile
    }

    override suspend fun saveBuyer(buyer: Buyer): Long {
        failSavesWith?.let { throw it }
        Validation.buyerProblem(buyer)?.let { throw UserFacingException(it) }
        if (buyer.id == 0L) {
            val saved = buyer.copy(id = nextBuyerId++)
            buyers.value = buyers.value + saved
            return saved.id
        }
        buyers.value = buyers.value.map { if (it.id == buyer.id) buyer else it }
        return buyer.id
    }

    override suspend fun deleteBuyer(buyerId: Long, restock: Boolean) {
        lastRestock = restock
        val gone = orders.value.filter { it.buyerId == buyerId }.map { it.id }.toSet()
        buyers.value = buyers.value.filterNot { it.id == buyerId }
        orders.value = orders.value.filterNot { it.id in gone }
        items.value = items.value.filterNot { it.orderId in gone }
    }

    /** Same rules as the Room repository: lines recomputed, order total = sum of lines, lines replaced on edit. */
    override suspend fun saveOrder(order: Order, items: List<OrderItem>, paidNow: BigDecimal): Long {
        failSavesWith?.let { throw it }
        val lines = items.mapIndexed { i, item -> item.copy(position = i, lineTotal = Money.total(item.unitPrice, item.quantity)) }
        Validation.itemsProblem(lines)?.let { throw UserFacingException(it) }
        val total = lines.fold(Money.ZERO) { sum, line -> sum + line.lineTotal }

        val isNew = order.id == 0L
        val id = if (isNew) nextOrderId++ else order.id
        if (isNew && paidNow.signum() > 0) {
            payments.value = payments.value + Payment(nextPaymentId++, id, paidNow, order.purchaseDateTime, order.paymentMode)
        }
        val saved = order.copy(id = id, totalAmount = total, paymentStatus = Payments.status(total, paidOf(id)))
        orders.value = orders.value.filterNot { it.id == id } + saved
        this.items.value = this.items.value.filterNot { it.orderId == id } + lines.map { it.copy(id = nextItemId++, orderId = id) }
        return id
    }

    private fun paidOf(orderId: Long) = Payments.paid(payments.value.filter { it.orderId == orderId })

    private fun refresh(orderId: Long) {
        orders.value = orders.value.map { if (it.id == orderId) it.copy(paymentStatus = Payments.status(it.totalAmount, paidOf(orderId))) else it }
    }

    override suspend fun recordPayment(orderId: Long, amount: BigDecimal, mode: PaymentMode, paidAt: Instant, note: String): Long {
        val order = orders.value.first { it.id == orderId }
        Payments.amountProblem(amount, Payments.balance(order.totalAmount, paidOf(orderId)))?.let { throw UserFacingException(it) }
        val payment = Payment(nextPaymentId++, orderId, amount, paidAt, mode, note)
        payments.value = payments.value + payment
        refresh(orderId)
        return payment.id
    }

    override suspend fun markPaid(orderId: Long): Long? {
        val order = orders.value.first { it.id == orderId }
        val balance = Payments.balance(order.totalAmount, paidOf(orderId))
        if (balance.signum() == 0) return null
        return recordPayment(orderId, balance, order.paymentMode, Instant.now())
    }

    override suspend fun deletePayment(paymentId: Long) {
        val payment = payments.value.firstOrNull { it.id == paymentId } ?: return
        payments.value = payments.value - payment
        refresh(payment.orderId)
    }

    override suspend fun setDelivery(orderId: Long, address: String, date: Instant?) {
        orders.value = orders.value.map { if (it.id == orderId) it.copy(deliveryAddress = address, deliveryDate = date) else it }
    }

    override suspend fun deleteOrder(orderId: Long, restock: Boolean) {
        lastRestock = restock
        payments.value = payments.value.filterNot { it.orderId == orderId }
        orders.value = orders.value.filterNot { it.id == orderId }
        items.value = items.value.filterNot { it.orderId == orderId }
    }

    override suspend fun setFulfillmentStatus(orderId: Long, status: FulfillmentStatus) {
        orders.value = orders.value.map { if (it.id == orderId) it.copy(fulfillmentStatus = status) else it }
    }


    fun orderCount() = orders.value.size
    fun itemCount() = items.value.size

    private fun List<Order>.sum(): BigDecimal = fold(Money.ZERO) { acc, o -> acc + o.totalAmount }
    private fun List<Order>.paidSum(): BigDecimal = fold(Money.ZERO) { acc, o -> acc + paidOf(o.id).min(o.totalAmount) }
    private fun List<Order>.balanceSum(): BigDecimal = fold(Money.ZERO) { acc, o -> acc + Payments.balance(o.totalAmount, paidOf(o.id)) }
}

class FakeDocuments : DocumentService {
    var failure: Exception? = null
    val receiptsRequested = mutableListOf<Long>()
    var workbooksCreated = 0
    var downloadFailure: Exception? = null
    val downloaded = mutableListOf<String>()

    val slipsRequested = mutableListOf<Long>()

    override suspend fun createOrderSlip(orderId: Long): ReceiptDocument {
        failure?.let { throw it }
        slipsRequested += orderId
        return ReceiptDocument(File("slip_$orderId.pdf"), "slip_$orderId.pdf", "Download/FergBentables/slip_$orderId.pdf", isOrderSlip = true)
    }

    override suspend fun createReceipt(orderId: Long): ReceiptDocument {
        failure?.let { throw it }
        receiptsRequested += orderId
        return ReceiptDocument(File("receipt_$orderId.pdf"), "receipt_$orderId.pdf", "Download/FergBentables/receipt_$orderId.pdf")
    }

    override suspend fun createWorkbook(): ExportDocument {
        failure?.let { throw it }
        workbooksCreated++
        return ExportDocument(File("export.xlsx"), "export.xlsx")
    }

    val buyerWorkbooks = mutableListOf<Long>()

    override suspend fun createBuyerWorkbook(buyerId: Long): ExportDocument {
        failure?.let { throw it }
        buyerWorkbooks += buyerId
        return ExportDocument(File("buyer_$buyerId.xlsx"), "Buyer_${buyerId}_Profile.xlsx")
    }

    override suspend fun createProductWorkbook(productId: Long): ExportDocument {
        failure?.let { throw it }
        return ExportDocument(File("product_$productId.xlsx"), "Product_${productId}_Inventory.xlsx")
    }

    override suspend fun createInventoryWorkbook(): ExportDocument {
        failure?.let { throw it }
        return ExportDocument(File("inventory.xlsx"), "Inventory_Report.xlsx")
    }

    override suspend fun saveToDownloads(export: ExportDocument): String {
        downloadFailure?.let { throw it }
        downloaded += export.displayName
        return "Download/FergBentables/${export.displayName}"
    }
}

class FakePhotos : PhotoStore {
    val discarded = mutableListOf<String?>()
    override suspend fun importPhoto(source: Uri): String = error("not used in these tests")
    override fun delete(imageUri: String?) {
        discarded += imageUri
    }
}
