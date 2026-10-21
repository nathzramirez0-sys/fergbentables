package com.blushdesk.app.ui.inventory

import com.blushdesk.app.data.local.database.InventoryNotification
import com.blushdesk.app.data.local.database.InventorySummary
import com.blushdesk.app.data.local.database.MovementRow
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ProductSale
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.utils.Money

/** The four parts of the Inventory System. */
enum class InventoryTab(val label: String) {
    OVERVIEW("Overview"),
    PRODUCTS("Products"),
    MOVEMENTS("Stock movements"),
    NOTIFICATIONS("Notifications"),
}

/** Which products the list shows. [ACTIVE] is everything not archived. */
enum class ProductFilter(val label: String) {
    ACTIVE("All"),
    IN_STOCK("In stock"),
    LOW_STOCK("Low stock"),
    OUT_OF_STOCK("Out of stock"),
    UNAVAILABLE("Unavailable"),
    ARCHIVED("Archived"),
}

enum class ProductSort(val label: String) {
    NAME("Name A–Z"),
    STOCK_LOW("Lowest stock first"),
    STOCK_HIGH("Highest stock first"),
    UPDATED("Recently updated"),
    PRICE_HIGH("Highest price first"),
}

/** Which movements the history shows. */
enum class MovementFilter(val label: String, val types: Set<MovementType>?) {
    ALL("All", null),
    STOCK_IN("Stock in", setOf(MovementType.STOCK_IN, MovementType.INITIAL)),
    SALES("Sales", setOf(MovementType.SALE)),
    RETURNS("Returns", setOf(MovementType.SALE_RETURN)),
    ADJUSTMENTS("Adjustments", setOf(MovementType.ADJUSTMENT)),
}

/** The product open in the Products tab, with its history and the order lines that sold it. */
data class ProductDetail(
    val product: Product,
    val movements: List<MovementRow>,
    val sales: List<ProductSale>,
)

/** Everything the Inventory System renders, as one snapshot. */
data class InventoryUiState(
    val loaded: Boolean = false,
    val products: List<Product> = emptyList(),
    val summary: InventorySummary = EMPTY_SUMMARY,
    val movements: List<MovementRow> = emptyList(),
    val notifications: List<InventoryNotification> = emptyList(),
    val unread: Int = 0,
    val tab: InventoryTab = InventoryTab.OVERVIEW,
    val query: String = "",
    val filter: ProductFilter = ProductFilter.ACTIVE,
    val sort: ProductSort = ProductSort.NAME,
    val movementQuery: String = "",
    val movementFilter: MovementFilter = MovementFilter.ALL,
    val selectedProductId: Long? = null,
    val detail: ProductDetail? = null,
    val exporting: Boolean = false,
) {
    val visibleProducts: List<Product> get() = InventoryQueries.products(products, query, filter, sort)
    val visibleMovements: List<MovementRow> get() = InventoryQueries.movements(movements, movementQuery, movementFilter)

    /** Low and out-of-stock products that are still for sale: what needs restocking. */
    val needsAttention: List<Product>
        get() = products.filter { it.stockStatus == StockStatus.LOW_STOCK || it.stockStatus == StockStatus.OUT_OF_STOCK }
            .sortedWith(compareBy({ it.quantity }, { it.name.lowercase() }))

    /** Existing categories, offered as suggestions in the product form. */
    val categories: List<String>
        get() = products.map { it.category.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    companion object {
        val EMPTY_SUMMARY = InventorySummary(
            totalProducts = 0, totalUnits = 0, inStock = 0, lowStock = 0, outOfStock = 0, unavailable = 0,
            archived = 0, stockValue = Money.ZERO, costValue = Money.ZERO, unitsSold = 0, unitsRestocked = 0,
        )
    }
}

/** Search, filter and sort, kept as plain functions so they are unit tested without a screen. */
object InventoryQueries {

    fun products(all: List<Product>, query: String, filter: ProductFilter, sort: ProductSort): List<Product> {
        val needle = query.trim().lowercase()
        return all
            .filter { p ->
                when (filter) {
                    ProductFilter.ACTIVE -> !p.archived
                    ProductFilter.IN_STOCK -> p.stockStatus == StockStatus.IN_STOCK
                    ProductFilter.LOW_STOCK -> p.stockStatus == StockStatus.LOW_STOCK
                    ProductFilter.OUT_OF_STOCK -> p.stockStatus == StockStatus.OUT_OF_STOCK
                    ProductFilter.UNAVAILABLE -> p.stockStatus == StockStatus.UNAVAILABLE || p.stockStatus == StockStatus.DISCONTINUED
                    ProductFilter.ARCHIVED -> p.archived
                }
            }
            .filter { p ->
                needle.isEmpty() ||
                    p.name.lowercase().contains(needle) ||
                    p.sku.orEmpty().lowercase().contains(needle) ||
                    p.category.lowercase().contains(needle) ||
                    p.description.lowercase().contains(needle)
            }
            .sortedWith(
                when (sort) {
                    ProductSort.NAME -> compareBy({ it.name.lowercase() }, { it.id })
                    ProductSort.STOCK_LOW -> compareBy<Product>({ it.quantity }, { it.name.lowercase() })
                    ProductSort.STOCK_HIGH -> compareByDescending<Product> { it.quantity }.thenBy { it.name.lowercase() }
                    ProductSort.UPDATED -> compareByDescending<Product> { it.updatedAt }.thenBy { it.name.lowercase() }
                    ProductSort.PRICE_HIGH -> compareByDescending<Product> { it.sellingPrice }.thenBy { it.name.lowercase() }
                },
            )
    }

    fun movements(all: List<MovementRow>, query: String, filter: MovementFilter): List<MovementRow> {
        val needle = query.trim().lowercase()
        return all.filter { row ->
            (filter.types == null || row.movement.type in filter.types) &&
                (needle.isEmpty() || row.productName.lowercase().contains(needle) || row.productSku.orEmpty().lowercase().contains(needle))
        }
    }
}
