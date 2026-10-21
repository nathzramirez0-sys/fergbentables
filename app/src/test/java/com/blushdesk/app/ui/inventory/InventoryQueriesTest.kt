package com.blushdesk.app.ui.inventory

import com.blushdesk.app.data.local.database.MovementRow
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.StockMovement
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.utils.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Search, filter and sort of the product list and the movement history. */
class InventoryQueriesTest {

    private fun product(id: Long, name: String, qty: Int, min: Int = 3, price: String = "100", sku: String? = null,
                        category: String = "", availability: Availability = Availability.AVAILABLE, archived: Boolean = false,
                        updated: String = "2026-10-01T00:00:00Z") =
        Product(
            id = id, name = name, sku = sku, category = category, sellingPrice = Money.of(price), quantity = qty, minimumStock = min,
            availability = availability, archived = archived, updatedAt = Instant.parse(updated),
        )

    private val keyboard = product(1, "USB Keyboard", 20, sku = "KB-01", category = "Electronics", price = "850", updated = "2026-10-05T00:00:00Z")
    private val mouse = product(2, "Wireless Mouse", 2, sku = "MS-02", category = "Electronics", price = "450")
    private val sofa = product(3, "Velvet Sofa", 0, category = "Furniture", price = "25000", updated = "2026-10-09T00:00:00Z")
    private val lamp = product(4, "Floor Lamp", 9, availability = Availability.UNAVAILABLE)
    private val old = product(5, "Old Chair", 4, archived = true)
    private val all = listOf(keyboard, mouse, sofa, lamp, old)

    private fun names(list: List<Product>) = list.map { it.name }

    @Test
    fun `all means everything except archived`() {
        assertEquals(listOf("Floor Lamp", "USB Keyboard", "Velvet Sofa", "Wireless Mouse"),
            names(InventoryQueries.products(all, "", ProductFilter.ACTIVE, ProductSort.NAME)))
    }

    @Test
    fun `each status filter shows only its products`() {
        assertEquals(listOf("USB Keyboard"), names(InventoryQueries.products(all, "", ProductFilter.IN_STOCK, ProductSort.NAME)))
        assertEquals(listOf("Wireless Mouse"), names(InventoryQueries.products(all, "", ProductFilter.LOW_STOCK, ProductSort.NAME)))
        assertEquals(listOf("Velvet Sofa"), names(InventoryQueries.products(all, "", ProductFilter.OUT_OF_STOCK, ProductSort.NAME)))
        assertEquals(listOf("Floor Lamp"), names(InventoryQueries.products(all, "", ProductFilter.UNAVAILABLE, ProductSort.NAME)))
        assertEquals(listOf("Old Chair"), names(InventoryQueries.products(all, "", ProductFilter.ARCHIVED, ProductSort.NAME)))
    }

    @Test
    fun `search matches name, SKU and category, ignoring case`() {
        assertEquals(listOf("USB Keyboard"), names(InventoryQueries.products(all, "kb-0", ProductFilter.ACTIVE, ProductSort.NAME)))
        assertEquals(listOf("USB Keyboard", "Wireless Mouse"), names(InventoryQueries.products(all, "ELECTRONICS", ProductFilter.ACTIVE, ProductSort.NAME)))
        assertEquals(listOf("Velvet Sofa"), names(InventoryQueries.products(all, "sofa", ProductFilter.ACTIVE, ProductSort.NAME)))
    }

    @Test
    fun `sorts by stock, recency and price`() {
        assertEquals(listOf("Velvet Sofa", "Wireless Mouse", "Floor Lamp", "USB Keyboard"),
            names(InventoryQueries.products(all, "", ProductFilter.ACTIVE, ProductSort.STOCK_LOW)))
        assertEquals("USB Keyboard", names(InventoryQueries.products(all, "", ProductFilter.ACTIVE, ProductSort.STOCK_HIGH)).first())
        assertEquals("Velvet Sofa", names(InventoryQueries.products(all, "", ProductFilter.ACTIVE, ProductSort.UPDATED)).first())
        assertEquals("Velvet Sofa", names(InventoryQueries.products(all, "", ProductFilter.ACTIVE, ProductSort.PRICE_HIGH)).first())
    }

    @Test
    fun `needs attention lists low and out-of-stock products for sale, emptiest first`() {
        assertEquals(listOf("Velvet Sofa", "Wireless Mouse"), names(InventoryUiState(products = all).needsAttention))
    }

    @Test
    fun `the movement history filters by kind and searches by product`() {
        fun row(id: Long, type: MovementType, name: String) = MovementRow(
            StockMovement(id = id, productId = id, type = type, previousQuantity = 0, change = 1, newQuantity = 1, occurredAt = Instant.EPOCH),
            productName = name, productSku = null, productUnit = "pc",
        )
        val rows = listOf(row(1, MovementType.INITIAL, "USB Keyboard"), row(2, MovementType.SALE, "USB Keyboard"), row(3, MovementType.STOCK_IN, "Mouse"))
        assertEquals(listOf(1L, 3L), InventoryQueries.movements(rows, "", MovementFilter.STOCK_IN).map { it.movement.id })
        assertEquals(listOf(2L), InventoryQueries.movements(rows, "", MovementFilter.SALES).map { it.movement.id })
        assertEquals(listOf(1L, 2L), InventoryQueries.movements(rows, "keyboard", MovementFilter.ALL).map { it.movement.id })
    }
}
