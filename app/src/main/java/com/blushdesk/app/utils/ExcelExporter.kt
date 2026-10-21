package com.blushdesk.app.utils

import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.data.local.database.BuyerWithOrders
import com.blushdesk.app.data.local.database.ExportSummary
import com.blushdesk.app.data.local.database.OperatorProfile
import com.blushdesk.app.data.local.database.InventorySummary
import com.blushdesk.app.data.local.database.MovementRow
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ProductSale
import com.blushdesk.app.domain.model.BuyerReport
import com.blushdesk.app.domain.model.ExportSnapshot
import com.blushdesk.app.domain.model.InventoryReport
import com.blushdesk.app.domain.model.ProductReport
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.theme.BrandPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator
import org.apache.poi.xssf.usermodel.XSSFRow
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Writes formatted .xlsx workbooks, on [Dispatchers.IO]:
 *  - [export]: the whole database. Items (opens first), Buyers, Orders, Operator, Summary, and the
 *    inventory's Inventory and Stock Movements sheets.
 *  - [exportBuyer]: one buyer's profile, items and orders, and nobody else's.
 *  - [exportProduct]: one product's details, stock history and sales.
 *  - [exportInventory]: the inventory report (products, movements, figures).
 *
 * Apache POI is a desktop-Java library, so a few of its habits are avoided on purpose:
 *  - No `autoSizeColumn`: it measures text with java.awt.font, which Android does not have.
 *    Column widths are fixed instead.
 *  - Formulas only where they earn it (the Items sheet's totals), and POI evaluates them before
 *    saving. Without the stored results, previewers that do not recalculate (mail and Drive
 *    viewers) would show blank totals. The other sheets hold plain values.
 *  - POI's ZIP output is rewritten as a plain ZIP; see [rewriteAsPlainZip] for why Excel on
 *    Android needs that.
 *  - The workbook is written to a `.part` file and renamed, so a failure never leaves a truncated
 *    file that could be shared as if it were good.
 *
 * Nothing here touches Android APIs, so the same class runs in plain JVM unit tests.
 */
class ExcelExporter(private val zone: ZoneId = ZoneId.systemDefault()) {

    /** Builds the full workbook for [snapshot] into [destination] and returns it. */
    suspend fun export(destination: File, snapshot: ExportSnapshot): File {
        val store = snapshot.operator.storeName.ifBlank { "Showroom" }
        return write(destination, "$store export") { workbook, styles ->
            writeItems(workbook.createSheet(SHEET_ITEMS), styles, snapshot.buyers)
            writeBuyers(workbook.createSheet(SHEET_BUYERS), styles, snapshot.buyers)
            writeOrders(workbook.createSheet(SHEET_ORDERS), styles, snapshot.buyers)
            writeOperator(workbook.createSheet(SHEET_OPERATOR), styles, snapshot.operator)
            val inventoryLines = snapshot.inventory?.let { inventoryLines(it, detailed = false) }.orEmpty()
            writeSummary(
                workbook.createSheet(SHEET_SUMMARY), styles, "$store - Summary", snapshot.takenAt,
                summaryLines(snapshot.summary) + inventoryLines,
            )
            writeInventory(workbook.createSheet(SHEET_INVENTORY), styles, snapshot.products)
            writeMovements(workbook.createSheet(SHEET_MOVEMENTS), styles, snapshot.movements)
        }
    }

    /** One buyer's profile export: Profile, then their Items and Orders. Nothing about other buyers. */
    suspend fun exportBuyer(destination: File, report: BuyerReport): File {
        val buyer = report.buyer.buyer
        return write(destination, "${buyer.fullName} profile") { workbook, styles ->
            writeDetails(workbook.createSheet(SHEET_PROFILE), styles, "${buyer.fullName} - Profile", report.takenAt, buyerDetails(report))
            writeItems(workbook.createSheet(SHEET_ITEMS), styles, listOf(report.buyer))
            writeOrders(workbook.createSheet(SHEET_ORDERS), styles, listOf(report.buyer))
        }
    }

    /** One product's profile export: Product details, its Stock Movements and its Sales. */
    suspend fun exportProduct(destination: File, report: ProductReport): File {
        val product = report.product
        return write(destination, "${product.name} inventory") { workbook, styles ->
            writeDetails(workbook.createSheet(SHEET_PRODUCT), styles, "${product.name} - Inventory", report.takenAt, productDetails(report))
            writeMovements(workbook.createSheet(SHEET_MOVEMENTS), styles, report.movements)
            writeSales(workbook.createSheet(SHEET_SALES), styles, report.sales)
        }
    }

    /** The inventory report: every product, every stock movement, and the inventory figures. */
    suspend fun exportInventory(destination: File, report: InventoryReport): File {
        val store = report.operator.storeName.ifBlank { "Showroom" }
        return write(destination, "$store inventory report") { workbook, styles ->
            writeInventory(workbook.createSheet(SHEET_INVENTORY), styles, report.products)
            writeMovements(workbook.createSheet(SHEET_MOVEMENTS), styles, report.movements)
            writeSummary(
                workbook.createSheet(SHEET_SUMMARY), styles, "$store - Inventory", report.takenAt,
                inventoryLines(report.summary, detailed = true),
            )
        }
    }

    /**
     * Writes a workbook built by [build] to [destination]: formulas evaluated, written to a raw
     * file, rewritten as a plain ZIP into a `.part` file, then renamed into place.
     */
    private suspend fun write(destination: File, title: String, build: (XSSFWorkbook, Styles) -> Unit): File = withContext(Dispatchers.IO) {
        val folder = destination.absoluteFile.parentFile
        folder?.mkdirs()
        val raw = File(folder, destination.name + ".raw")
        val partial = File(folder, destination.name + ".part")
        try {
            XSSFWorkbook().use { workbook ->
                build(workbook, Styles(workbook))
                // Store each formula's result next to it, for viewers that never calculate.
                XSSFFormulaEvaluator.evaluateAllFormulaCells(workbook)

                workbook.properties.coreProperties.apply {
                    creator = APP_NAME
                    this.title = title
                }
                FileOutputStream(raw).use { workbook.write(it) }
            }
            rewriteAsPlainZip(raw, partial)
            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
        } catch (e: Throwable) {
            partial.delete()
            throw e
        } finally {
            raw.delete()
        }
        destination
    }

    /**
     * POI streams every part of the package into the ZIP with a "data descriptor": the local
     * header says zero for the CRC and sizes, and the real values follow the data. Desktop Excel
     * reads the central directory and does not mind. Excel for Android refuses such a file and
     * calls it password-protected. Copying the entries into a seekable file lets commons-compress
     * go back and write the real CRC and sizes into every local header, the way Excel saves its own
     * files. ZIP64 and the UTF-8 name flag are left off too: an export is far below 4 GB and every
     * part name is ASCII.
     */
    private fun rewriteAsPlainZip(source: File, target: File) {
        ZipFile(source).use { zip ->
            ZipArchiveOutputStream(target).use { out ->
                out.setUseZip64(Zip64Mode.Never)
                out.setUseLanguageEncodingFlag(false)
                for (entry in zip.entries()) {
                    out.putArchiveEntry(
                        ZipArchiveEntry(entry.name).apply {
                            method = ZipEntry.DEFLATED
                            time = entry.time
                        },
                    )
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeArchiveEntry()
                }
            }
        }
    }

    // ---- Worksheet 1: Items -----------------------------------------------------------------

    /**
     * The sheet to read on a tablet: each order's products as Product Name / Quantity / Price /
     * Total Amount, newest order first, each order with its total and a grand total at the end.
     * The totals are formulas, so the sheet still adds up if someone edits a quantity or price in
     * Excel. The grand total adds the rows labelled [ORDER_TOTAL_LABEL]. Each total row also says
     * whether that order is [PAID] or [NOT_YET_PAID], so a printout shows who still owes.
     */
    private fun writeItems(sheet: XSSFSheet, styles: Styles, buyers: List<BuyerWithOrders>) {
        writeHeader(sheet, styles, ITEM_COLUMNS, widths = listOf(40, 12, 16, 18))
        sheet.createFreezePane(0, 1)
        fitToWidth(sheet, landscape = false)

        val orders = ordersOf(buyers).reversed()
        if (orders.isEmpty()) {
            sheet.createRow(1).text(0, "No orders yet", styles.subtitle)
            return
        }

        var row = 1
        orders.forEach { (buyer, withItems) ->
            val order = withItems.order
            val title = "${Formats.orderNumber(order.id)} · ${buyer.fullName} · ${Formats.date(order.purchaseDateTime, zone)}"
            sheet.createRow(row).apply {
                text(0, title, styles.orderTitle)
                (1..3).forEach { createCell(it).cellStyle = styles.orderTitle }
            }
            sheet.addMergedRegion(CellRangeAddress(row, row, 0, 3))
            row++

            val firstLine = row + 1 // formulas use Excel's 1-based row numbers
            withItems.items.forEach { item ->
                val excelRow = row + 1
                sheet.createRow(row).apply {
                    text(0, item.productName, styles.body(Kind.TEXT, false))
                    number(1, item.quantity.toDouble(), styles.body(Kind.COUNT, false))
                    number(2, Money.toExcelNumber(item.unitPrice), styles.body(Kind.MONEY, false))
                    formula(3, "ROUND(B$excelRow*C$excelRow,2)", styles.body(Kind.MONEY, false))
                }
                row++
            }
            sheet.createRow(row).apply {
                val paid = order.paymentStatus.isPaid
                text(0, if (paid) PAID else NOT_YET_PAID, styles.badge(BrandPalette.tone(if (paid) PaymentStatus.PAID else PaymentStatus.UNPAID)))
                text(2, ORDER_TOTAL_LABEL, styles.orderTotalLabel)
                formula(3, "SUM(D$firstLine:D$row)", styles.body(Kind.MONEY_BOLD, false))
            }
            row += 2 // the total, then a blank row before the next order
        }

        sheet.createRow(row).apply {
            heightInPoints = 22f
            text(0, "GRAND TOTAL", styles.grandTotalLabel)
            (1..2).forEach { createCell(it).cellStyle = styles.grandTotalLabel }
            formula(3, "SUMIF(C2:C$row,\"$ORDER_TOTAL_LABEL\",D2:D$row)", styles.grandTotal)
        }
        sheet.addMergedRegion(CellRangeAddress(row, row, 0, 2))
    }

    // ---- Worksheet 2: Buyers ----------------------------------------------------------------

    private fun writeBuyers(sheet: XSSFSheet, styles: Styles, buyers: List<BuyerWithOrders>) {
        writeHeader(sheet, styles, BUYER_COLUMNS, widths = listOf(10, 26, 18, 24, 30, 16, 18, 18))
        buyers.forEachIndexed { index, (buyer, orders) ->
            val zebra = index % 2 == 1
            sheet.createRow(index + 1).apply {
                number(0, buyer.id.toDouble(), styles.body(Kind.ID, zebra))
                text(1, buyer.fullName, styles.body(Kind.TEXT, zebra))
                text(2, buyer.contactNumber, styles.body(Kind.TEXT, zebra))
                text(3, buyer.facebookName, styles.body(Kind.TEXT, zebra))
                text(4, buyer.email, styles.body(Kind.TEXT, zebra))
                createCell(5).apply {
                    setCellValue(buyer.dateAdded.atZone(zone).toLocalDate())
                    cellStyle = styles.body(Kind.DATE, zebra)
                }
                number(6, orders.size.toDouble(), styles.body(Kind.COUNT, zebra))
                when (val status = buyerPaymentStatus(orders)) {
                    PAID -> text(7, status, styles.badge(BrandPalette.tone(PaymentStatus.PAID)))
                    NOT_YET_PAID -> text(7, status, styles.badge(BrandPalette.tone(PaymentStatus.UNPAID)))
                    else -> text(7, status, styles.body(Kind.TEXT, zebra))
                }
            }
        }
        finishTable(sheet, BUYER_COLUMNS.size, buyers.size)
    }

    /**
     * A buyer is [PAID] once every one of their orders is marked Paid, and [NOT_YET_PAID] while any
     * order is still Unpaid or Pending. A buyer without orders owes nothing but has not paid
     * anything either, so they get [NO_ORDERS] rather than a misleading "Paid".
     */
    private fun buyerPaymentStatus(orders: List<OrderWithItems>): String = when {
        orders.isEmpty() -> NO_ORDERS
        orders.all { it.order.paymentStatus.isPaid } -> PAID
        else -> NOT_YET_PAID
    }

    // ---- Worksheet 3: Orders ----------------------------------------------------------------

    /**
     * One row per product line, so an order with three products takes three rows sharing the same
     * Order ID. "Total Amount" is that line's unit price x quantity; "Order Total" repeats the
     * whole order's total on each of its rows. Zebra striping alternates per order, not per row.
     */
    private fun writeOrders(sheet: XSSFSheet, styles: Styles, buyers: List<BuyerWithOrders>) {
        writeHeader(sheet, styles, ORDER_COLUMNS, widths = listOf(10, 10, 24, 32, 15, 10, 16, 15, 14, 17, 16, 18, 16))
        val orders = ordersOf(buyers)

        var rowIndex = 1
        orders.forEachIndexed { orderIndex, (buyer, withItems) ->
            val order = withItems.order
            val zebra = orderIndex % 2 == 1
            val purchased = order.purchaseDateTime.atZone(zone)
            withItems.items.forEach { item -> sheet.createRow(rowIndex++).apply {
                number(0, order.id.toDouble(), styles.body(Kind.ID, zebra))
                number(1, buyer.id.toDouble(), styles.body(Kind.ID, zebra))
                text(2, buyer.fullName, styles.body(Kind.TEXT, zebra))
                text(3, item.productName, styles.body(Kind.TEXT, zebra))
                number(4, Money.toExcelNumber(item.unitPrice), styles.body(Kind.MONEY, zebra))
                number(5, item.quantity.toDouble(), styles.body(Kind.COUNT, zebra))
                number(6, Money.toExcelNumber(item.lineTotal), styles.body(Kind.MONEY, zebra))
                createCell(7).apply {
                    setCellValue(purchased.toLocalDate())
                    cellStyle = styles.body(Kind.DATE, zebra)
                }
                // An Excel time is the fraction of a day.
                number(8, purchased.toLocalTime().toSecondOfDay() / SECONDS_PER_DAY, styles.body(Kind.TIME, zebra))
                text(9, order.paymentMode.label, styles.body(Kind.TEXT, zebra))
                text(10, order.paymentStatus.label, styles.badge(BrandPalette.tone(order.paymentStatus)))
                text(11, order.fulfillmentStatus.label, styles.badge(BrandPalette.tone(order.fulfillmentStatus)))
                number(12, Money.toExcelNumber(order.totalAmount), styles.body(Kind.MONEY_BOLD, zebra))
            } }
        }
        finishTable(sheet, ORDER_COLUMNS.size, rowIndex - 1)
    }

    // ---- Worksheet 4: Operator --------------------------------------------------------------

    private fun writeOperator(sheet: XSSFSheet, styles: Styles, operator: OperatorProfile) {
        writeHeader(sheet, styles, OPERATOR_COLUMNS, widths = listOf(26, 28, 30, 18))
        sheet.createRow(1).apply {
            text(0, operator.fullName, styles.body(Kind.TEXT, false))
            text(1, operator.storeName, styles.body(Kind.TEXT, false))
            text(2, operator.email, styles.body(Kind.TEXT, false))
            text(3, operator.phoneNumber, styles.body(Kind.TEXT, false))
        }
        sheet.createFreezePane(0, 1)
        fitToWidth(sheet)
    }

    // ---- Worksheet 5: Summary ---------------------------------------------------------------

    private fun writeSummary(sheet: XSSFSheet, styles: Styles, title: String, takenAt: Instant, lines: List<Pair<String, SummaryValue>>) =
        writeKeyValues(sheet, styles, title, takenAt, "Metric", lines, valueWidth = 22)

    /** A details sheet (one buyer, one product): the same title-and-two-columns layout as Summary. */
    private fun writeDetails(sheet: XSSFSheet, styles: Styles, title: String, takenAt: Instant, lines: List<Pair<String, SummaryValue>>) =
        writeKeyValues(sheet, styles, title, takenAt, "Field", lines, valueWidth = 44)

    private fun writeKeyValues(
        sheet: XSSFSheet,
        styles: Styles,
        title: String,
        takenAt: Instant,
        keyTitle: String,
        lines: List<Pair<String, SummaryValue>>,
        valueWidth: Int,
    ) {
        sheet.setColumnWidth(0, 30 * 256)
        sheet.setColumnWidth(1, valueWidth * 256)
        sheet.setDisplayGridlines(false)

        sheet.createRow(0).apply {
            heightInPoints = 30f
            text(0, title, styles.title)
        }
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 1))
        sheet.createRow(1).text(0, "Generated ${Formats.dateTime(takenAt, zone)}", styles.subtitle)

        val headerRow = SUMMARY_HEADER_ROW
        sheet.createRow(headerRow).apply {
            heightInPoints = 22f
            text(0, keyTitle, styles.header)
            text(1, "Value", styles.header)
        }
        lines.forEachIndexed { index, (label, value) ->
            val zebra = index % 2 == 1
            sheet.createRow(headerRow + 1 + index).apply {
                text(0, label, styles.label(zebra))
                when (value) {
                    is SummaryValue.Count -> number(1, value.count.toDouble(), styles.body(Kind.COUNT, zebra))
                    is SummaryValue.Amount -> number(1, Money.toExcelNumber(value.amount), styles.body(Kind.MONEY_BOLD, zebra))
                    is SummaryValue.Text -> text(1, value.text, styles.body(Kind.TEXT, zebra))
                    is SummaryValue.Badge -> text(1, value.text, styles.badge(value.tone))
                }
            }
        }
        fitToWidth(sheet)
    }

    private sealed interface SummaryValue {
        data class Count(val count: Int) : SummaryValue
        data class Amount(val amount: java.math.BigDecimal) : SummaryValue
        data class Text(val text: String) : SummaryValue
        data class Badge(val text: String, val tone: BrandPalette.Tone) : SummaryValue
    }

    /** Inventory figures, for the full export's Summary ([detailed] = false) and the inventory report. */
    private fun inventoryLines(s: InventorySummary, detailed: Boolean): List<Pair<String, SummaryValue>> = buildList {
        add("Total Products" to SummaryValue.Count(s.totalProducts))
        add("Total Stock Units" to SummaryValue.Count(s.totalUnits))
        add("Products In Stock" to SummaryValue.Count(s.inStock))
        add("Low Stock Products" to SummaryValue.Count(s.lowStock))
        add("Out of Stock Products" to SummaryValue.Count(s.outOfStock))
        add("Unavailable Products" to SummaryValue.Count(s.unavailable))
        if (detailed) add("Archived Products" to SummaryValue.Count(s.archived))
        add("Stock Value (Selling Price)" to SummaryValue.Amount(s.stockValue))
        if (detailed) {
            add("Stock Value (Cost Price)" to SummaryValue.Amount(s.costValue))
            add("Units Sold" to SummaryValue.Count(s.unitsSold))
            add("Units Restocked" to SummaryValue.Count(s.unitsRestocked))
        }
    }

    private fun buyerDetails(report: BuyerReport): List<Pair<String, SummaryValue>> {
        val buyer = report.buyer.buyer
        val orders = report.buyer.orders
        val total = orders.fold(Money.ZERO) { sum, o -> sum + o.order.totalAmount }
        val paid = orders.filter { it.order.paymentStatus.isPaid }.fold(Money.ZERO) { sum, o -> sum + o.order.totalAmount }
        val status = buyerPaymentStatus(orders)
        return listOf(
            "Buyer ID" to SummaryValue.Text(buyer.id.toString()),
            "Full Name" to SummaryValue.Text(buyer.fullName),
            "Contact Number" to SummaryValue.Text(buyer.contactNumber),
            "Facebook Name" to SummaryValue.Text(buyer.facebookName),
            "Email" to SummaryValue.Text(buyer.email),
            "Date Added" to SummaryValue.Text(Formats.date(buyer.dateAdded, zone)),
            "Number of Orders" to SummaryValue.Count(orders.size),
            "Units Purchased" to SummaryValue.Count(orders.sumOf { it.unitCount }),
            "Total Amount" to SummaryValue.Amount(total),
            "Paid" to SummaryValue.Amount(paid),
            "Outstanding" to SummaryValue.Amount(total - paid),
            "Payment Status" to when (status) {
                PAID -> SummaryValue.Badge(status, BrandPalette.tone(PaymentStatus.PAID))
                NOT_YET_PAID -> SummaryValue.Badge(status, BrandPalette.tone(PaymentStatus.UNPAID))
                else -> SummaryValue.Text(status)
            },
        )
    }

    private fun productDetails(report: ProductReport): List<Pair<String, SummaryValue>> {
        val p = report.product
        val status = p.stockStatus
        val sold = report.sales.sumOf { it.quantity }
        return listOf(
            "Product ID" to SummaryValue.Text(p.id.toString()),
            "SKU" to SummaryValue.Text(p.sku.orEmpty()),
            "Product Name" to SummaryValue.Text(p.name),
            "Category" to SummaryValue.Text(p.category),
            "Description" to SummaryValue.Text(p.description),
            "Unit" to SummaryValue.Text(p.unit),
            "Selling Price" to SummaryValue.Amount(p.sellingPrice),
            "Cost Price" to (p.costPrice?.let { SummaryValue.Amount(it) } ?: SummaryValue.Text("Not set")),
            "Current Stock" to SummaryValue.Count(p.quantity),
            "Minimum Stock" to SummaryValue.Count(p.minimumStock),
            "Stock Status" to SummaryValue.Badge(status.label, BrandPalette.tone(status)),
            "Availability" to SummaryValue.Text(p.availability.label),
            "Stock Value" to SummaryValue.Amount(Money.total(p.sellingPrice, p.quantity)),
            "Units Sold (on current orders)" to SummaryValue.Count(sold),
            "Stock Reminder" to SummaryValue.Text(p.reminderInterval.label),
            "Created" to SummaryValue.Text(Formats.dateTime(p.createdAt, zone)),
            "Last Updated" to SummaryValue.Text(Formats.dateTime(p.updatedAt, zone)),
        )
    }

    // ---- Inventory sheets -------------------------------------------------------------------

    /** Every product, archived ones included (their status says so), one row each. */
    private fun writeInventory(sheet: XSSFSheet, styles: Styles, products: List<Product>) {
        writeHeader(sheet, styles, INVENTORY_COLUMNS, widths = listOf(11, 14, 30, 18, 8, 15, 15, 14, 14, 15, 15, 16, 16, 18))
        products.forEachIndexed { index, p ->
            val zebra = index % 2 == 1
            val status = p.stockStatus
            sheet.createRow(index + 1).apply {
                number(0, p.id.toDouble(), styles.body(Kind.ID, zebra))
                text(1, p.sku.orEmpty(), styles.body(Kind.TEXT, zebra))
                text(2, p.name, styles.body(Kind.TEXT, zebra))
                text(3, p.category, styles.body(Kind.TEXT, zebra))
                text(4, p.unit, styles.body(Kind.TEXT, zebra))
                number(5, Money.toExcelNumber(p.sellingPrice), styles.body(Kind.MONEY, zebra))
                p.costPrice?.let { number(6, Money.toExcelNumber(it), styles.body(Kind.MONEY, zebra)) } ?: text(6, "", styles.body(Kind.TEXT, zebra))
                number(7, p.quantity.toDouble(), styles.body(Kind.COUNT, zebra))
                number(8, p.minimumStock.toDouble(), styles.body(Kind.COUNT, zebra))
                text(9, status.label, styles.badge(BrandPalette.tone(status)))
                text(10, p.availability.label, styles.body(Kind.TEXT, zebra))
                number(11, Money.toExcelNumber(Money.total(p.sellingPrice, p.quantity)), styles.body(Kind.MONEY, zebra))
                text(12, p.reminderInterval.label, styles.body(Kind.TEXT, zebra))
                createCell(13).apply {
                    setCellValue(p.updatedAt.atZone(zone).toLocalDateTime())
                    cellStyle = styles.body(Kind.DATE_TIME, zebra)
                }
            }
        }
        if (products.isEmpty()) sheet.createRow(1).text(0, "No products yet", styles.subtitle)
        finishTable(sheet, INVENTORY_COLUMNS.size, products.size)
    }

    /** The audit trail, oldest first: every change to every quantity, with who and why. */
    private fun writeMovements(sheet: XSSFSheet, styles: Styles, movements: List<MovementRow>) {
        writeHeader(sheet, styles, MOVEMENT_COLUMNS, widths = listOf(14, 11, 11, 14, 28, 20, 12, 10, 12, 13, 34, 20, 16, 18))
        movements.forEachIndexed { index, row ->
            val m = row.movement
            val zebra = index % 2 == 1
            val at = m.occurredAt.atZone(zone)
            sheet.createRow(index + 1).apply {
                createCell(0).apply {
                    setCellValue(at.toLocalDate())
                    cellStyle = styles.body(Kind.DATE, zebra)
                }
                number(1, at.toLocalTime().toSecondOfDay() / SECONDS_PER_DAY, styles.body(Kind.TIME, zebra))
                number(2, m.productId.toDouble(), styles.body(Kind.ID, zebra))
                text(3, row.productSku.orEmpty(), styles.body(Kind.TEXT, zebra))
                text(4, row.productName, styles.body(Kind.TEXT, zebra))
                text(5, m.type.label, styles.body(Kind.TEXT, zebra))
                number(6, m.previousQuantity.toDouble(), styles.body(Kind.COUNT, zebra))
                number(7, m.change.toDouble(), styles.body(Kind.SIGNED, zebra))
                number(8, m.newQuantity.toDouble(), styles.body(Kind.COUNT, zebra))
                text(9, m.orderId?.let(Formats::orderNumber).orEmpty(), styles.body(Kind.TEXT, zebra))
                text(10, m.reason, styles.body(Kind.TEXT, zebra))
                text(11, m.supplier, styles.body(Kind.TEXT, zebra))
                text(12, m.reference, styles.body(Kind.TEXT, zebra))
                text(13, m.performedBy, styles.body(Kind.TEXT, zebra))
            }
        }
        if (movements.isEmpty()) sheet.createRow(1).text(0, "No stock movements yet", styles.subtitle)
        finishTable(sheet, MOVEMENT_COLUMNS.size, movements.size)
    }

    /** Every order line that sold one product, oldest first. */
    private fun writeSales(sheet: XSSFSheet, styles: Styles, sales: List<ProductSale>) {
        writeHeader(sheet, styles, SALE_COLUMNS, widths = listOf(13, 26, 14, 11, 10, 15, 15, 16, 18))
        sales.forEachIndexed { index, sale ->
            val zebra = index % 2 == 1
            sheet.createRow(index + 1).apply {
                text(0, Formats.orderNumber(sale.orderId), styles.body(Kind.TEXT, zebra))
                text(1, sale.buyerName, styles.body(Kind.TEXT, zebra))
                createCell(2).apply {
                    setCellValue(sale.purchaseDateTime.atZone(zone).toLocalDate())
                    cellStyle = styles.body(Kind.DATE, zebra)
                }
                number(3, sale.purchaseDateTime.atZone(zone).toLocalTime().toSecondOfDay() / SECONDS_PER_DAY, styles.body(Kind.TIME, zebra))
                number(4, sale.quantity.toDouble(), styles.body(Kind.COUNT, zebra))
                number(5, Money.toExcelNumber(sale.unitPrice), styles.body(Kind.MONEY, zebra))
                number(6, Money.toExcelNumber(sale.lineTotal), styles.body(Kind.MONEY, zebra))
                text(7, sale.paymentStatus.label, styles.badge(BrandPalette.tone(sale.paymentStatus)))
                text(8, sale.fulfillmentStatus.label, styles.badge(BrandPalette.tone(sale.fulfillmentStatus)))
            }
        }
        if (sales.isEmpty()) sheet.createRow(1).text(0, "Not on any order yet", styles.subtitle)
        finishTable(sheet, SALE_COLUMNS.size, sales.size)
    }

    private fun summaryLines(s: ExportSummary): List<Pair<String, SummaryValue>> = listOf(
        "Total Buyers" to SummaryValue.Count(s.totalBuyers),
        "Total Orders" to SummaryValue.Count(s.totalOrders),
        "Paid Orders" to SummaryValue.Count(s.paidOrders),
        "Unpaid Orders" to SummaryValue.Count(s.unpaidOrders),
        "Pending Orders" to SummaryValue.Count(s.pendingOrders),
        "Processing Orders" to SummaryValue.Count(s.processingOrders),
        "Preparing Orders" to SummaryValue.Count(s.preparingOrders),
        "Delivered Orders" to SummaryValue.Count(s.deliveredOrders),
        "Total Recorded Sales" to SummaryValue.Amount(s.totalRecordedSales),
    )

    // ---- Shared sheet helpers ---------------------------------------------------------------

    /** Every order with its buyer, oldest first (ties broken by id, so the order is stable). */
    private fun ordersOf(buyers: List<BuyerWithOrders>): List<Pair<Buyer, OrderWithItems>> = buyers
        .flatMap { entry -> entry.orders.map { entry.buyer to it } }
        .sortedWith(compareBy({ it.second.order.purchaseDateTime }, { it.second.order.id }))

    private fun writeHeader(sheet: XSSFSheet, styles: Styles, titles: List<String>, widths: List<Int>) {
        val header = sheet.createRow(0)
        header.heightInPoints = 24f
        titles.forEachIndexed { index, title ->
            header.text(index, title, styles.header)
            sheet.setColumnWidth(index, widths[index] * 256)
        }
    }

    /** Freeze the header, add filter dropdowns and print landscape on one page width. */
    private fun finishTable(sheet: XSSFSheet, columnCount: Int, dataRows: Int) {
        sheet.createFreezePane(0, 1)
        sheet.setAutoFilter(CellRangeAddress(0, maxOf(dataRows, 1), 0, columnCount - 1))
        fitToWidth(sheet)
    }

    private fun fitToWidth(sheet: XSSFSheet, landscape: Boolean = true) {
        sheet.printSetup.landscape = landscape
        sheet.fitToPage = true
        sheet.printSetup.fitWidth = 1
        sheet.printSetup.fitHeight = 0
    }

    private fun XSSFRow.text(col: Int, value: String, style: XSSFCellStyle) {
        createCell(col).apply {
            setCellValue(value)
            cellStyle = style
        }
    }

    private fun XSSFRow.number(col: Int, value: Double, style: XSSFCellStyle) {
        createCell(col).apply {
            setCellValue(value)
            cellStyle = style
        }
    }

    /** [formula] is written without the leading "=", as POI expects. */
    private fun XSSFRow.formula(col: Int, formula: String, style: XSSFCellStyle) {
        createCell(col).apply {
            cellFormula = formula
            cellStyle = style
        }
    }

    private enum class Kind { TEXT, ID, COUNT, SIGNED, MONEY, MONEY_BOLD, DATE, TIME, DATE_TIME }

    /**
     * Cell styles are workbook-wide objects with a hard cap (64,000 in .xlsx), so each distinct
     * look is created once and reused for every cell that needs it.
     */
    private class Styles(private val workbook: XSSFWorkbook) {
        private val cache = HashMap<String, XSSFCellStyle>()
        private val formats = workbook.createDataFormat()

        val title = cached("title") {
            setFont(font(size = 18, bold = true, color = BrandPalette.DEEP_MAGENTA))
            verticalAlignment = VerticalAlignment.CENTER
        }
        val subtitle = cached("subtitle") {
            setFont(font(size = 10, italic = true, color = BrandPalette.MUTED_TEXT))
        }
        val header = cached("header") {
            setFont(font(size = 11, bold = true, color = BrandPalette.WHITE))
            fill(BrandPalette.VIBRANT_ROSE)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = true
            border()
        }

        val orderTitle = cached("order-title") {
            setFont(font(bold = true, color = BrandPalette.DEEP_MAGENTA))
            fill(BrandPalette.LAVENDER_BLUSH)
            verticalAlignment = VerticalAlignment.CENTER
            border()
        }
        val orderTotalLabel = cached("order-total-label") {
            setFont(font(bold = true, color = BrandPalette.DEEP_MAGENTA))
            alignment = HorizontalAlignment.RIGHT
        }
        val grandTotalLabel = cached("grand-total-label") {
            setFont(font(size = 12, bold = true, color = BrandPalette.WHITE))
            fill(BrandPalette.VIBRANT_ROSE)
            verticalAlignment = VerticalAlignment.CENTER
            border()
        }
        val grandTotal = cached("grand-total") {
            setFont(font(size = 12, bold = true, color = BrandPalette.WHITE))
            fill(BrandPalette.VIBRANT_ROSE)
            verticalAlignment = VerticalAlignment.CENTER
            dataFormat = formats.getFormat("\"${Money.SYMBOL}\"#,##0.00")
            border()
        }

        fun label(zebra: Boolean): XSSFCellStyle = cached("label-$zebra") {
            setFont(font(bold = true, color = BrandPalette.DEEP_MAGENTA))
            if (zebra) fill(BrandPalette.LAVENDER_BLUSH)
            border()
        }

        fun body(kind: Kind, zebra: Boolean): XSSFCellStyle = cached("body-$kind-$zebra") {
            setFont(font(bold = kind == Kind.MONEY_BOLD))
            if (zebra) fill(BrandPalette.LAVENDER_BLUSH)
            verticalAlignment = VerticalAlignment.CENTER
            border()
            when (kind) {
                Kind.TEXT -> Unit
                Kind.ID -> dataFormat = formats.getFormat("0")
                Kind.COUNT -> dataFormat = formats.getFormat("#,##0")
                Kind.SIGNED -> dataFormat = formats.getFormat("+#,##0;-#,##0;0")
                Kind.MONEY, Kind.MONEY_BOLD -> dataFormat = formats.getFormat("\"${Money.SYMBOL}\"#,##0.00")
                Kind.DATE -> dataFormat = formats.getFormat("d mmm yyyy")
                Kind.TIME -> dataFormat = formats.getFormat("h:mm AM/PM")
                Kind.DATE_TIME -> dataFormat = formats.getFormat("d mmm yyyy h:mm AM/PM")
            }
            if (kind == Kind.DATE || kind == Kind.TIME || kind == Kind.DATE_TIME || kind == Kind.ID) alignment = HorizontalAlignment.LEFT
        }

        fun badge(tone: BrandPalette.Tone): XSSFCellStyle = cached("badge-${tone.foreground}-${tone.background}") {
            setFont(font(bold = true, color = tone.foreground))
            fill(tone.background)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            border()
        }

        private fun cached(key: String, build: XSSFCellStyle.() -> Unit): XSSFCellStyle =
            cache.getOrPut(key) { workbook.createCellStyle().apply(build) }

        private fun font(
            size: Int = 11,
            bold: Boolean = false,
            italic: Boolean = false,
            color: Int = BrandPalette.DARK_TEXT,
        ): XSSFFont = workbook.createFont().apply {
            fontHeightInPoints = size.toShort()
            this.bold = bold
            this.italic = italic
            setColor(rgb(color))
        }

        private fun XSSFCellStyle.fill(rgb: Int) {
            setFillForegroundColor(rgb(rgb))
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        private fun XSSFCellStyle.border() {
            val color = rgb(BrandPalette.OUTLINE_SOFT)
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
            setTopBorderColor(color)
            setBottomBorderColor(color)
            setLeftBorderColor(color)
            setRightBorderColor(color)
        }

        private fun rgb(value: Int) = XSSFColor(
            byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte()),
            null,
        )
    }

    companion object {
        const val SHEET_ITEMS = "Items"
        const val SHEET_BUYERS = "Buyers"
        const val SHEET_ORDERS = "Orders"
        const val SHEET_OPERATOR = "Operator"
        const val SHEET_SUMMARY = "Summary"
        const val SHEET_INVENTORY = "Inventory"
        const val SHEET_MOVEMENTS = "Stock Movements"
        const val SHEET_PROFILE = "Profile"
        const val SHEET_PRODUCT = "Product"
        const val SHEET_SALES = "Sales"

        /** Row index of the "Metric | Value" header on the Summary sheet (after title and timestamp). */
        const val SUMMARY_HEADER_ROW = 3

        /** Not in the original specification: asked for later, to read orders on a tablet. */
        val ITEM_COLUMNS = listOf("Product Name", "Quantity", "Price", "Total Amount")

        /** Marks each order's total row on the Items sheet; the grand total's SUMIF looks for it. */
        const val ORDER_TOTAL_LABEL = "Order total"

        val BUYER_COLUMNS = listOf(
            "Buyer ID", "Full Name", "Contact Number",
            // Not in the original specification: the alternative to a contact number.
            "Facebook Name",
            "Email", "Date Added", "Number of Orders",
            // Not in the original specification: whether the buyer has paid for everything.
            "Payment Status",
        )

        /** The buyer's (Buyers sheet) or the order's (Items sheet) payment, in the client's words. */
        const val PAID = "Paid"
        const val NOT_YET_PAID = "Not Yet Paid"
        const val NO_ORDERS = "No orders"

        val ORDER_COLUMNS = listOf(
            "Order ID", "Buyer ID", "Buyer Name", "Product", "Unit Price", "Quantity", "Total Amount",
            "Purchase Date", "Purchase Time", "Payment Mode", "Payment Status", "Fulfillment Status",
            // Not in the original specification: added with multi-product orders.
            "Order Total",
        )

        val OPERATOR_COLUMNS = listOf("Operator Name", "Store Name", "Email", "Phone Number")

        val INVENTORY_COLUMNS = listOf(
            "Product ID", "SKU", "Product Name", "Category", "Unit", "Selling Price", "Cost Price",
            "Current Stock", "Minimum Stock", "Stock Status", "Availability", "Stock Value",
            "Stock Reminder", "Last Updated",
        )

        val MOVEMENT_COLUMNS = listOf(
            "Date", "Time", "Product ID", "SKU", "Product", "Movement", "Previous Stock", "Change",
            "New Stock", "Order", "Reason / Notes", "Supplier", "Reference", "Performed By",
        )

        val SALE_COLUMNS = listOf(
            "Order", "Buyer", "Purchase Date", "Time", "Quantity", "Unit Price", "Line Total",
            "Payment Status", "Fulfillment Status",
        )

        private const val SECONDS_PER_DAY = 86_400.0
    }
}
