package com.blushdesk.app.utils

import android.content.Context
import android.util.Log
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.domain.repository.InventoryRepository
import com.blushdesk.app.domain.repository.ShowroomRepository
import kotlinx.coroutines.CancellationException
import java.io.File

/** A generated receipt: the local file to open or share, and where its Downloads copy landed. */
data class ReceiptDocument(
    val file: File,
    val displayName: String,
    /** "Download/<APP_NAME>/<name>", or null if the copy to Downloads failed (the local file is still good). */
    val savedTo: String?,
    /** An order slip (an order with a balance) rather than a paid receipt. */
    val isOrderSlip: Boolean = false,
)

/** A generated workbook, saved locally and ready for the share sheet or for Downloads. */
data class ExportDocument(val file: File, val displayName: String)

/**
 * Produces the two kinds of file the app creates. Behind an interface so the ViewModel can be
 * unit tested without a device: the real implementation needs PdfDocument and MediaStore.
 */
interface DocumentService {
    /** The PDF receipt of a paid order, also copied to Downloads. */
    suspend fun createReceipt(orderId: Long): ReceiptDocument

    /** The PDF order slip of an order with a balance, showing what is still due; also copied to Downloads. */
    suspend fun createOrderSlip(orderId: Long): ReceiptDocument

    /** The .xlsx with every record in the database. */
    suspend fun createWorkbook(): ExportDocument

    /** One buyer's profile, items and orders: Juan_Dela_Cruz_Profile_2026-10-20.xlsx. */
    suspend fun createBuyerWorkbook(buyerId: Long): ExportDocument

    /** One product's details, stock history and sales: USB_Keyboard_Inventory_2026-10-20.xlsx. */
    suspend fun createProductWorkbook(productId: Long): ExportDocument

    /** Every product and stock movement with the inventory figures: Inventory_Report_2026-10-20.xlsx. */
    suspend fun createInventoryWorkbook(): ExportDocument

    /** Copies [export] to Downloads and returns where it landed ("Download/<APP_NAME>/<name>"). */
    suspend fun saveToDownloads(export: ExportDocument): String
}

class AndroidDocumentService(
    private val context: Context,
    private val repository: ShowroomRepository,
    private val inventory: InventoryRepository,
    private val excel: ExcelExporter,
    private val pdf: PdfReceiptGenerator,
    private val downloads: DownloadsSaver,
) : DocumentService {

    override suspend fun createReceipt(orderId: Long): ReceiptDocument = createPdf(orderId, slip = false)

    override suspend fun createOrderSlip(orderId: Long): ReceiptDocument = createPdf(orderId, slip = true)

    private suspend fun createPdf(orderId: Long, slip: Boolean): ReceiptDocument {
        val order = repository.getOrder(orderId) ?: throw UserFacingException("That order no longer exists")
        if (!slip && !order.order.paymentStatus.isPaid) throw UserFacingException("Mark the order as paid before issuing a receipt")
        if (slip && order.order.paymentStatus.isPaid) throw UserFacingException("This order is paid. Make the paid receipt instead.")
        val buyer = repository.getBuyer(order.order.buyerId) ?: throw UserFacingException("That buyer no longer exists")
        val operator = repository.getOperator()

        val folder = AppFiles.cacheDir(context, AppFiles.RECEIPTS_DIR)
        val kind = if (slip) "OrderSlip" else "Receipt"
        val name = "${kind}_${Formats.orderNumber(order.order)}_${Formats.fileSafe(buyer.fullName)}.pdf"
        val file = pdf.generate(File(folder, name), operator, buyer, order, slip = slip)

        // The receipt is already complete in the cache; failing to copy it to Downloads must not lose it.
        val savedTo = try {
            downloads.save(file, name, AppFiles.MIME_PDF)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy receipt to Downloads", e)
            null
        }
        AppFiles.prune(folder, keep = 20)
        return ReceiptDocument(file, name, savedTo, isOrderSlip = slip)
    }

    override suspend fun createWorkbook(): ExportDocument {
        val snapshot = repository.getExportSnapshot()
        if (snapshot.buyers.isEmpty() && snapshot.products.isEmpty()) {
            throw UserFacingException("There is nothing to export yet. Add a buyer or a product first.")
        }

        val folder = AppFiles.cacheDir(context, AppFiles.EXPORTS_DIR)
        val name = "${APP_NAME}_Export_${Formats.fileStamp(snapshot.takenAt)}.xlsx"
        val file = excel.export(File(folder, name), snapshot)
        AppFiles.prune(folder, keep = 5)
        return ExportDocument(file, name)
    }

    override suspend fun createBuyerWorkbook(buyerId: Long): ExportDocument {
        val report = repository.getBuyerReport(buyerId)
        val name = "${Formats.fileSafe(report.buyer.buyer.fullName)}_Profile_${Formats.fileDate(report.takenAt)}.xlsx"
        return exportFile(name) { excel.exportBuyer(it, report) }
    }

    override suspend fun createProductWorkbook(productId: Long): ExportDocument {
        val report = inventory.getProductReport(productId)
        val name = "${Formats.fileSafe(report.product.name, fallback = "product")}_Inventory_${Formats.fileDate(report.takenAt)}.xlsx"
        return exportFile(name) { excel.exportProduct(it, report) }
    }

    override suspend fun createInventoryWorkbook(): ExportDocument {
        val report = inventory.getInventoryReport()
        val name = "Inventory_Report_${Formats.fileDate(report.takenAt)}.xlsx"
        return exportFile(name) { excel.exportInventory(it, report) }
    }

    /** Writes an export into the shared exports cache folder and keeps only the newest few there. */
    private suspend fun exportFile(name: String, write: suspend (File) -> File): ExportDocument {
        val folder = AppFiles.cacheDir(context, AppFiles.EXPORTS_DIR)
        val file = write(File(folder, name))
        AppFiles.prune(folder, keep = 5)
        return ExportDocument(file, name)
    }

    // Unlike a receipt, the workbook was asked for as a download, so a failed copy is the action failing.
    override suspend fun saveToDownloads(export: ExportDocument): String =
        downloads.save(export.file, export.displayName, AppFiles.MIME_XLSX)

    private companion object {
        const val TAG = "DocumentService"
    }
}
