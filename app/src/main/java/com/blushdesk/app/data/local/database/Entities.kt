package com.blushdesk.app.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.NotificationKind
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.domain.model.stockStatusOf
import com.blushdesk.app.utils.Money
import java.math.BigDecimal
import java.time.Instant

/**
 * The person running the tablet. The app keeps exactly one active profile (id [ACTIVE_ID]);
 * saving replaces it. Receipts and the Excel export take their showroom details from here.
 *
 * Image fields hold a `file://` URI of a copy in the app's private storage, never a picker URI
 * (those stop working) and never shared with other apps.
 */
@Entity(tableName = "operator_profile")
data class OperatorProfile(
    @PrimaryKey val id: Int = ACTIVE_ID,
    val fullName: String = "",
    val storeName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val profileImageUri: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) {
    /** False until the two fields every receipt needs are filled in. */
    val isSetUp: Boolean get() = fullName.isNotBlank() && storeName.isNotBlank()

    companion object {
        const val ACTIVE_ID = 1
    }
}

/**
 * A customer. They can be reached by [contactNumber], by [facebookName], or both; the rule that at
 * least one is filled in lives in [com.blushdesk.app.utils.Validation.buyerContact]. Email is
 * optional.
 */
@Entity(
    tableName = "buyers",
    indices = [Index("fullName")],
)
data class Buyer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fullName: String,
    val contactNumber: String = "",
    /** Added in schema version 4; buyers recorded before that have an empty one. */
    @ColumnInfo(defaultValue = "") val facebookName: String = "",
    val email: String = "",
    val dateAdded: Instant,
    val profileImageUri: String? = null,
    val createdAt: Instant = dateAdded,
    val updatedAt: Instant = dateAdded,
)

/**
 * One purchase: when, how it was paid, where it is in the lifecycle, and its total. What was
 * bought is in [OrderItem] (one row per product). `Buyer.id -> Order.buyerId`; deleting a buyer
 * deletes their orders, and deleting an order deletes its items (both CASCADE).
 *
 * Money is [BigDecimal] in code and whole centavos (INTEGER) in the database, see [Converters]:
 * exact in both places, unlike Double. [totalAmount] is the sum of the items' line totals; it is
 * stored so SQL can total sales quickly, but the repository recomputes it on every save.
 */
@Entity(
    tableName = "orders",
    foreignKeys = [
        ForeignKey(
            entity = Buyer::class,
            parentColumns = ["id"],
            childColumns = ["buyerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("buyerId")],
)
data class Order(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val buyerId: Long,
    val totalAmount: BigDecimal = Money.ZERO,
    val purchaseDateTime: Instant,
    val paymentMode: PaymentMode,
    val paymentStatus: PaymentStatus,
    val fulfillmentStatus: FulfillmentStatus = FulfillmentStatus.PROCESSING,
    val createdAt: Instant = purchaseDateTime,
    val updatedAt: Instant = purchaseDateTime,
)

/**
 * One product line of an order. [lineTotal] = unitPrice x quantity, recomputed on every save and
 * never typed in. [position] keeps the lines in the order the operator entered them.
 *
 * [productId] links the line to the inventory (schema version 5): saving the order takes the
 * quantity out of that product's stock. Null for a custom item typed in by hand, and for every
 * line recorded before the inventory existed. [productName] and [unitPrice] are a copy taken at
 * the time of sale, so renaming or repricing a product never rewrites an old order or receipt.
 * A product that appears on an order can be archived but not deleted (RESTRICT).
 */
@Entity(
    tableName = "order_items",
    foreignKeys = [
        ForeignKey(
            entity = Order::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("orderId"), Index("productId")],
)
data class OrderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long = 0,
    val position: Int = 0,
    val productName: String,
    val unitPrice: BigDecimal,
    val quantity: Int,
    val lineTotal: BigDecimal = Money.total(unitPrice, quantity),
    val productId: Long? = null,
)

/**
 * A product the showroom stocks (schema version 5).
 *
 * [quantity] is only ever changed by the inventory ledger, which writes a [StockMovement] for
 * every change; editing a product never touches it. [sku] is optional but unique when given, and
 * stored upper-case so "ab-1" and "AB-1" count as the same code. [availability] is the operator's
 * choice; the stock label shown everywhere is derived from it and the quantity
 * ([com.blushdesk.app.domain.model.stockStatusOf]). Archiving hides a product from new orders
 * while every past order keeps pointing at it.
 *
 * The reminder fields drive the "May stock pa" notification: [reminderNextAt] is when it is next
 * due (null when off), [reminderLastSentAt] when it last went out. [lowStockAlerted] is set when a
 * low-stock alert goes out and cleared once the quantity is back above the minimum, so each dip
 * alerts once.
 */
@Entity(
    tableName = "products",
    indices = [Index(value = ["sku"], unique = true), Index("name"), Index("archived")],
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String? = null,
    val name: String,
    val category: String = "",
    val description: String = "",
    val sellingPrice: BigDecimal,
    val costPrice: BigDecimal? = null,
    val quantity: Int = 0,
    val minimumStock: Int = 0,
    val unit: String = DEFAULT_UNIT,
    val availability: Availability = Availability.AVAILABLE,
    val archived: Boolean = false,
    val imageUri: String? = null,
    val reminderInterval: ReminderInterval = ReminderInterval.NEVER,
    val reminderLastSentAt: Instant? = null,
    val reminderNextAt: Instant? = null,
    val lowStockAlerted: Boolean = false,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) {
    val stockStatus: StockStatus get() = stockStatusOf(quantity, minimumStock, availability, archived)

    /** Can go on a new order line (stock permitting). */
    val isSellable: Boolean get() = !archived && availability == Availability.AVAILABLE

    companion object {
        const val DEFAULT_UNIT = "pc"
    }
}

/**
 * One change to a product's quantity: the audit trail. [previousQuantity] + [change] =
 * [newQuantity] always holds. [orderId] names the order behind a sale or its reversal; it has no
 * foreign key on purpose, so the record survives the order being deleted. [occurredAt] is when it
 * happened (a delivery's date can be back-dated), [createdAt] when it was recorded.
 */
@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("productId"), Index("orderId"), Index("occurredAt")],
)
data class StockMovement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val type: MovementType,
    val previousQuantity: Int,
    val change: Int,
    val newQuantity: Int,
    val orderId: Long? = null,
    val reason: String = "",
    val supplier: String = "",
    val reference: String = "",
    val performedBy: String = "",
    val occurredAt: Instant,
    val createdAt: Instant = occurredAt,
)

/**
 * An inventory notification, kept as history in the app and shown as a phone notification once
 * ([postedAt]). [dedupKey] is unique, so the same reminder or alert can never be recorded twice
 * even if the background job runs twice for the same moment.
 */
@Entity(
    tableName = "inventory_notifications",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("productId"), Index(value = ["dedupKey"], unique = true), Index("createdAt")],
)
data class InventoryNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val kind: NotificationKind,
    val title: String,
    val message: String,
    val dedupKey: String,
    val createdAt: Instant,
    val readAt: Instant? = null,
    val postedAt: Instant? = null,
)
