package com.blushdesk.app.utils

import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerWithOrders
import com.blushdesk.app.data.local.database.ExportSummary
import com.blushdesk.app.data.local.database.InventorySummary
import com.blushdesk.app.data.local.database.MovementRow
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ProductSale
import com.blushdesk.app.data.local.database.StockMovement
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.BuyerReport
import com.blushdesk.app.domain.model.ExportSnapshot
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.InventoryReport
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.ProductReport
import com.blushdesk.app.domain.model.ReminderInterval
import kotlinx.coroutines.test.runTest
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** The inventory sheets of the full export, and the buyer, product and inventory workbooks. */
class InventoryExcelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val exporter = ExcelExporter(ZoneId.of("Asia/Manila"))
    private val operator = OperatorProfile(fullName = "Lia Santos", storeName = "Rosé Showroom")
    private val takenAt = Instant.parse("2026-10-20T02:00:00Z")

    private val keyboard = Product(
        id = 1, sku = "KB-01", name = "USB Keyboard", category = "Electronics", sellingPrice = Money.of("850"),
        costPrice = Money.of("500"), quantity = 17, minimumStock = 5, reminderInterval = ReminderInterval.WEEKLY,
        createdAt = Instant.parse("2026-10-01T02:00:00Z"), updatedAt = Instant.parse("2026-10-19T02:00:00Z"),
    )
    private val mouse = Product(id = 2, name = "Wireless Mouse", sellingPrice = Money.of("450"), quantity = 0, minimumStock = 2)
    private val lamp = Product(id = 3, name = "Floor Lamp", sellingPrice = Money.of("999.99"), quantity = 4, availability = Availability.UNAVAILABLE)

    private fun movement(id: Long, type: MovementType, before: Int, change: Int, orderId: Long? = null, reason: String = "") = MovementRow(
        StockMovement(
            id = id, productId = 1, type = type, previousQuantity = before, change = change, newQuantity = before + change,
            orderId = orderId, reason = reason, performedBy = if (orderId != null) "System" else "Lia Santos",
            occurredAt = Instant.parse("2026-10-1${id}T02:00:00Z"),
        ),
        productName = "USB Keyboard", productSku = "KB-01", productUnit = "pc",
    )

    private val movements = listOf(
        movement(1, MovementType.INITIAL, 0, 20, reason = "Product added"),
        movement(2, MovementType.SALE, 20, -3, orderId = 12, reason = "Order BD-000012"),
    )

    private val ana = Buyer(id = 1, fullName = "Ana Reyes", contactNumber = "0917 123 4567", dateAdded = Instant.parse("2026-09-01T02:00:00Z"))
    private val ben = Buyer(id = 2, fullName = "Ben Cruz", facebookName = "Ben Cruz Home", dateAdded = Instant.parse("2026-09-05T02:00:00Z"))

    private fun order(id: Long, buyer: Buyer, pay: PaymentStatus, vararg items: OrderItem) = OrderWithItems(
        Order(
            id = id, buyerId = buyer.id, totalAmount = items.fold(Money.ZERO) { s, i -> s + i.lineTotal },
            purchaseDateTime = Instant.parse("2026-10-1${id % 10}T03:00:00Z"), paymentMode = PaymentMode.CASH,
            paymentStatus = pay, fulfillmentStatus = FulfillmentStatus.PROCESSING,
        ),
        items.map { it.copy(orderId = id) },
    )

    private val buyers = listOf(
        BuyerWithOrders(ana, listOf(order(12, ana, PaymentStatus.PAID, OrderItem(productName = "USB Keyboard", unitPrice = Money.of("850"), quantity = 3, productId = 1)))),
        BuyerWithOrders(ben, listOf(order(13, ben, PaymentStatus.UNPAID, OrderItem(productName = "Custom Shelf", unitPrice = Money.of("2000"), quantity = 1)))),
    )

    private val inventory = InventorySummary(
        totalProducts = 3, totalUnits = 21, inStock = 1, lowStock = 0, outOfStock = 1, unavailable = 1, archived = 0,
        stockValue = Money.of("18449.96"), costValue = Money.of("8500"), unitsSold = 3, unitsRestocked = 0,
    )

    private fun open(file: File) = XSSFWorkbook(file.inputStream())
    private fun XSSFSheet.text(row: Int, col: Int): String = getRow(row).getCell(col).stringCellValue
    private fun XSSFSheet.number(row: Int, col: Int): Double = getRow(row).getCell(col).numericCellValue
    private fun XSSFSheet.headers(count: Int) = (0 until count).map { text(0, it) }

    /** Value of a "Field | Value" (or "Metric | Value") row, found by its label. */
    private fun XSSFSheet.valueOf(label: String) =
        (ExcelExporter.SUMMARY_HEADER_ROW + 1..lastRowNum).map { getRow(it) }.first { it.getCell(0).stringCellValue == label }.getCell(1)

    @Test
    fun `the full export adds Inventory and Stock Movements sheets, and inventory figures to the Summary`() = runTest {
        val file = exporter.export(
            File(folder.root, "full.xlsx"),
            ExportSnapshot(
                operator, buyers,
                ExportSummary(2, 2, 1, 1, 0, 2, 0, 0, Money.of("4550")),
                takenAt, products = listOf(lamp, keyboard, mouse), movements = movements, inventory = inventory,
            ),
        )
        open(file).use { wb ->
            val sheet = wb.getSheet(ExcelExporter.SHEET_INVENTORY)
            assertEquals(ExcelExporter.INVENTORY_COLUMNS, sheet.headers(ExcelExporter.INVENTORY_COLUMNS.size))
            assertEquals("USB Keyboard", sheet.text(2, 2))
            assertEquals(17.0, sheet.number(2, 7), 0.0)
            assertEquals("In stock", sheet.text(2, 9))
            assertEquals("Out of stock", sheet.text(3, 9))
            assertEquals("Unavailable", sheet.text(1, 9))
            assertEquals(14450.0, sheet.number(2, 11), 0.001) // 850 x 17
            // "20 Oct 2026 10:39 PM" is 20 characters; a narrower column prints as ##### in Excel.
            val lastUpdated = ExcelExporter.INVENTORY_COLUMNS.indexOf("Last Updated")
            assertTrue(sheet.getColumnWidth(lastUpdated) / 256 >= 21)

            val history = wb.getSheet(ExcelExporter.SHEET_MOVEMENTS)
            assertEquals(ExcelExporter.MOVEMENT_COLUMNS, history.headers(ExcelExporter.MOVEMENT_COLUMNS.size))
            assertEquals("Sale", history.text(2, 5))
            assertEquals(-3.0, history.number(2, 7), 0.0)
            assertEquals(17.0, history.number(2, 8), 0.0)
            assertEquals("BD-000012", history.text(2, 9))
            assertEquals("System", history.text(2, 13))

            // Every bold header fits on one line: "Previous Stock" used to wrap and be cut off.
            for ((table, columns) in listOf(sheet to ExcelExporter.INVENTORY_COLUMNS, history to ExcelExporter.MOVEMENT_COLUMNS)) {
                columns.forEachIndexed { col, header ->
                    assertTrue("$header is too narrow", table.getColumnWidth(col) / 256 >= header.length + 1)
                }
            }

            val summary = wb.getSheet(ExcelExporter.SHEET_SUMMARY)
            assertEquals(3.0, summary.valueOf("Total Products").numericCellValue, 0.0)
            assertEquals(1.0, summary.valueOf("Out of Stock Products").numericCellValue, 0.0)
            // The original figures are still there, first.
            assertEquals("Total Buyers", summary.text(ExcelExporter.SUMMARY_HEADER_ROW + 1, 0))
        }
    }

    @Test
    fun `a buyer export holds only that buyer`() = runTest {
        val file = exporter.exportBuyer(File(folder.root, "ana.xlsx"), BuyerReport(operator, buyers[0], takenAt))
        open(file).use { wb ->
            assertEquals(listOf("Profile", "Items", "Orders"), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })
            val profile = wb.getSheet("Profile")
            assertEquals("Ana Reyes", profile.valueOf("Full Name").stringCellValue)
            assertEquals("Paid", profile.valueOf("Payment Status").stringCellValue)
            assertEquals(2550.0, profile.valueOf("Total Amount").numericCellValue, 0.001)
            val orders = wb.getSheet("Orders")
            assertEquals(1, orders.lastRowNum) // header + Ana's one line; nothing of Ben's
            assertEquals("Ana Reyes", orders.text(1, 2))
            val everything = (0 until wb.numberOfSheets).flatMap { i -> wb.getSheetAt(i).flatMap { row -> row.map { it.toString() } } }
            assertTrue(everything.none { it.contains("Ben Cruz") || it.contains("Custom Shelf") })
        }
    }

    @Test
    fun `a product export has its details, stock history and sales`() = runTest {
        val sale = ProductSale(12, 1, "Ana Reyes", Instant.parse("2026-10-12T03:00:00Z"), 3, Money.of("850"), Money.of("2550"), PaymentStatus.PAID, FulfillmentStatus.PROCESSING)
        val file = exporter.exportProduct(File(folder.root, "kb.xlsx"), ProductReport(operator, keyboard, movements, listOf(sale), takenAt))
        open(file).use { wb ->
            assertEquals(listOf("Product", "Stock Movements", "Sales"), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })
            val product = wb.getSheet("Product")
            assertEquals("KB-01", product.valueOf("SKU").stringCellValue)
            assertEquals(17.0, product.valueOf("Current Stock").numericCellValue, 0.0)
            assertEquals("In stock", product.valueOf("Stock Status").stringCellValue)
            assertEquals("Every 1 week", product.valueOf("Stock Reminder").stringCellValue)
            assertEquals(2, wb.getSheet("Stock Movements").lastRowNum)
            val sales = wb.getSheet("Sales")
            assertEquals(ExcelExporter.SALE_COLUMNS, sales.headers(ExcelExporter.SALE_COLUMNS.size))
            ExcelExporter.SALE_COLUMNS.forEachIndexed { col, header ->
                assertTrue("$header is too narrow", sales.getColumnWidth(col) / 256 >= header.length + 1)
            }
            assertEquals("BD-000012", sales.text(1, 0))
            assertEquals(3.0, sales.number(1, 4), 0.0)
        }
    }

    @Test
    fun `the inventory report has every product, every movement and the stock figures`() = runTest {
        val file = exporter.exportInventory(
            File(folder.root, "inventory.xlsx"),
            InventoryReport(operator, listOf(lamp, keyboard, mouse), movements, inventory, takenAt),
        )
        open(file).use { wb ->
            assertEquals(listOf("Inventory", "Stock Movements", "Summary"), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })
            assertEquals(3, wb.getSheet("Inventory").lastRowNum)
            val summary = wb.getSheet("Summary")
            assertEquals(3.0, summary.valueOf("Units Sold").numericCellValue, 0.0)
            assertEquals(8500.0, summary.valueOf("Stock Value (Cost Price)").numericCellValue, 0.001)
        }
    }

    @Test
    fun `an empty inventory still exports readable sheets`() = runTest {
        val empty = inventory.copy(totalProducts = 0, totalUnits = 0, inStock = 0, outOfStock = 0, unavailable = 0, stockValue = Money.ZERO, costValue = Money.ZERO, unitsSold = 0)
        val file = exporter.exportInventory(File(folder.root, "empty.xlsx"), InventoryReport(operator, emptyList(), emptyList(), empty, takenAt))
        open(file).use { wb ->
            assertEquals("No products yet", wb.getSheet("Inventory").text(1, 0))
            assertEquals("No stock movements yet", wb.getSheet("Stock Movements").text(1, 0))
        }
    }
}
