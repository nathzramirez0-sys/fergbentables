package com.blushdesk.app.domain.model

import com.blushdesk.app.data.local.database.BuyerWithOrders
import com.blushdesk.app.data.local.database.ExportSummary
import com.blushdesk.app.data.local.database.InventorySummary
import com.blushdesk.app.data.local.database.MovementRow
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ProductSale
import java.time.Instant

/**
 * Everything the full Excel export writes, read in one database transaction so the Summary sheet's
 * figures always agree with the other sheets. [inventory] is null only when built by hand in tests
 * that predate the inventory; the repository always fills it.
 */
data class ExportSnapshot(
    val operator: OperatorProfile,
    val buyers: List<BuyerWithOrders>,
    val summary: ExportSummary,
    val takenAt: Instant,
    val products: List<Product> = emptyList(),
    val movements: List<MovementRow> = emptyList(),
    val inventory: InventorySummary? = null,
)

/** One buyer's profile export: the buyer with every order and item, and nothing about anyone else. */
data class BuyerReport(
    val operator: OperatorProfile,
    val buyer: BuyerWithOrders,
    val takenAt: Instant,
)

/** One product's profile export: the product, its stock history and every order line that sold it. */
data class ProductReport(
    val operator: OperatorProfile,
    val product: Product,
    val movements: List<MovementRow>,
    val sales: List<ProductSale>,
    val takenAt: Instant,
)

/** The inventory report: every product, every stock movement and the inventory figures. */
data class InventoryReport(
    val operator: OperatorProfile,
    val products: List<Product>,
    val movements: List<MovementRow>,
    val summary: InventorySummary,
    val takenAt: Instant,
)
