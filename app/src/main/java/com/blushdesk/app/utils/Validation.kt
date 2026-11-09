package com.blushdesk.app.utils

import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.OrderItem
import java.math.BigDecimal

/**
 * Input rules shared by the dialogs (which show the message under the field) and the repository
 * (which refuses to store a record that breaks them). Each function returns a message written for
 * the operator, or null when the value is fine.
 */
object Validation {
    const val MAX_NAME = 80
    const val MAX_PRODUCT = 100
    const val MAX_QUANTITY = 9_999

    /** Product lines per order; keeps a receipt to a few pages and a total far inside a Long. */
    const val MAX_ITEMS = 30

    private val EMAIL = Regex("""^[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}$""")
    private val PHONE_CHARS = Regex("""^[0-9+()\-.\s]+$""")

    fun name(value: String, what: String = "Name"): String? = when {
        value.isBlank() -> "$what is required"
        value.trim().length > MAX_NAME -> "Keep it under $MAX_NAME characters"
        else -> null
    }

    /** Email is optional; when given it must look like one. */
    fun email(value: String): String? = when {
        value.isBlank() -> null
        !EMAIL.matches(value.trim()) -> "Enter a valid email address"
        else -> null
    }

    fun phone(value: String, required: Boolean = true): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return if (required) "Contact number is required" else null
        if (!PHONE_CHARS.matches(trimmed) || trimmed.count { it.isDigit() } < 7) {
            return "Enter a valid phone number"
        }
        return null
    }

    /** A Facebook name is free text, so a display name or a profile link both work. */
    fun facebookName(value: String): String? =
        if (value.trim().length > MAX_NAME) "Keep it under $MAX_NAME characters" else null

    /**
     * A buyer has to be reachable: a contact number, a Facebook name, or both. The number is only
     * checked when one is given.
     */
    fun buyerContact(phone: String, facebookName: String): String? = when {
        phone.isBlank() && facebookName.isBlank() -> "Add a contact number or a Facebook name"
        else -> phone(phone, required = false)
    }

    fun product(value: String): String? = when {
        value.isBlank() -> "Product name is required"
        value.trim().length > MAX_PRODUCT -> "Keep it under $MAX_PRODUCT characters"
        else -> null
    }

    /** For the price as typed. */
    fun unitPrice(input: String): String? {
        val amount = Money.parse(input) ?: return "Enter an amount like 1250 or 1250.50"
        return unitPrice(amount)
    }

    fun unitPrice(amount: BigDecimal): String? = when {
        amount.signum() <= 0 -> "Price must be greater than zero"
        amount.stripTrailingZeros().scale() > 2 -> "Use at most two decimals"
        amount > Money.MAX_UNIT_PRICE -> "Price is too large"
        else -> null
    }

    /** For the quantity as typed. */
    fun quantity(input: String): String? {
        val qty = input.trim().toIntOrNull() ?: return "Enter a whole number"
        return quantity(qty)
    }

    fun quantity(qty: Int): String? = when {
        qty < 1 -> "Quantity must be at least 1"
        qty > MAX_QUANTITY -> "Quantity can be at most $MAX_QUANTITY"
        else -> null
    }

    /** The first problem with a buyer about to be saved, or null. */
    fun buyerProblem(buyer: Buyer): String? =
        name(buyer.fullName, "Buyer name")
            ?: buyerContact(buyer.contactNumber, buyer.facebookName)
            ?: facebookName(buyer.facebookName)
            ?: email(buyer.email)

    /** The first problem with one product line, or null. */
    fun itemProblem(item: OrderItem): String? =
        product(item.productName) ?: unitPrice(item.unitPrice) ?: quantity(item.quantity)

    /** The first problem with an order's product lines, or null. */
    fun itemsProblem(items: List<OrderItem>): String? = when {
        items.isEmpty() -> "Add at least one product"
        items.size > MAX_ITEMS -> "An order can have at most $MAX_ITEMS products"
        else -> items.firstNotNullOfOrNull { itemProblem(it) }
    }

    // ---- Inventory --------------------------------------------------------------------------

    /** Units a product can hold; far above any showroom, and far inside an Int. */
    const val MAX_STOCK = 999_999
    private const val MAX_STOCK_TEXT = "999,999"
    const val MAX_SKU = 30
    const val MAX_CATEGORY = 40
    const val MAX_UNIT = 12
    const val MAX_DESCRIPTION = 300
    const val MAX_NOTE = 200
    const val MAX_SUPPLIER = 80
    const val MAX_REFERENCE = 40

    private val SKU_CHARS = Regex("""^[A-Z0-9][A-Z0-9._/\-]*$""")

    /** Upper-cased and trimmed; blank becomes null. Stored this way so codes compare exactly. */
    fun normalizeSku(value: String?): String? = value?.trim()?.uppercase()?.ifEmpty { null }

    /** A SKU is optional; when given: letters, digits and . _ / - only. */
    fun sku(value: String?): String? {
        val sku = normalizeSku(value) ?: return null
        return when {
            sku.length > MAX_SKU -> "Keep it under $MAX_SKU characters"
            !SKU_CHARS.matches(sku) -> "Use letters, numbers, dots, dashes or slashes"
            else -> null
        }
    }

    fun category(value: String): String? =
        if (value.trim().length > MAX_CATEGORY) "Keep it under $MAX_CATEGORY characters" else null

    fun unit(value: String): String? = when {
        value.isBlank() -> "Unit is required, for example pc, set or box"
        value.trim().length > MAX_UNIT -> "Keep it under $MAX_UNIT characters"
        else -> null
    }

    fun description(value: String): String? =
        if (value.trim().length > MAX_DESCRIPTION) "Keep it under $MAX_DESCRIPTION characters" else null

    /** Cost price is optional; when given it may be zero (a free item) but not more than the maximum. */
    fun costPrice(input: String): String? {
        if (input.isBlank()) return null
        val amount = Money.parse(input) ?: return "Enter an amount like 950 or 950.50"
        return costPrice(amount)
    }

    fun costPrice(amount: BigDecimal?): String? = when {
        amount == null -> null
        amount.signum() < 0 -> "Cost can't be negative"
        amount.stripTrailingZeros().scale() > 2 -> "Use at most two decimals"
        amount > Money.MAX_UNIT_PRICE -> "Cost is too large"
        else -> null
    }

    /** A stock count as typed (initial stock, minimum level): a whole number, zero allowed. */
    fun stockCount(input: String, what: String = "Stock"): String? {
        val count = input.trim().toIntOrNull() ?: return "Enter a whole number"
        return stockCount(count, what)
    }

    fun stockCount(count: Int, what: String = "Stock"): String? = when {
        count < 0 -> "$what can't be negative"
        count > MAX_STOCK -> "$what can be at most $MAX_STOCK_TEXT"
        else -> null
    }

    /** Units received in one delivery. */
    fun stockIn(input: String): String? {
        val qty = input.trim().toIntOrNull() ?: return "Enter a whole number"
        return when {
            qty < 1 -> "Enter at least 1"
            qty > MAX_STOCK -> "Enter at most $MAX_STOCK_TEXT"
            else -> null
        }
    }

    /** A manual adjustment: a non-zero whole number, signed. */
    fun adjustment(change: Int?): String? = when {
        change == null -> "Enter a whole number"
        change == 0 -> "Enter how many to add or remove"
        kotlin.math.abs(change) > MAX_STOCK -> "Enter at most $MAX_STOCK_TEXT"
        else -> null
    }

    fun note(value: String, max: Int = MAX_NOTE): String? =
        if (value.trim().length > max) "Keep it under $max characters" else null

    /** The first problem with a product about to be saved, or null. */
    fun productProblem(name: String, sku: String?, category: String, unit: String, description: String,
                       sellingPrice: BigDecimal, costPrice: BigDecimal?, minimumStock: Int): String? =
        product(name)
            ?: sku(sku)
            ?: category(category)
            ?: unit(unit)
            ?: description(description)
            ?: unitPrice(sellingPrice)
            ?: costPrice(costPrice)
            ?: stockCount(minimumStock, "Minimum stock")

    /** The start of new order numbers: 1 to 4 capital letters or digits, beginning with a letter (RS, FB, RS2). */
    fun orderPrefix(value: String): String? = when {
        value.isBlank() -> "Enter a prefix, like BD or RS"
        !ORDER_PREFIX.matches(value.trim()) -> "Use 1 to 4 letters or digits, starting with a letter"
        else -> null
    }

    private val ORDER_PREFIX = Regex("[A-Z][A-Z0-9]{0,3}")
}
