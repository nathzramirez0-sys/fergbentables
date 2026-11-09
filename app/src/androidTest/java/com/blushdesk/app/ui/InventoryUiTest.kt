package com.blushdesk.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blushdesk.app.TestData
import com.blushdesk.app.data.backup.BackupService
import com.blushdesk.app.data.backup.BackupStore
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.repository.OfflineInventoryRepository
import com.blushdesk.app.data.repository.OfflineShowroomRepository
import com.blushdesk.app.domain.model.AdjustmentReason
import com.blushdesk.app.ui.backup.BackupViewModel
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.inventory.InventoryScreen
import com.blushdesk.app.ui.inventory.InventoryViewModel
import com.blushdesk.app.ui.inventory.ProductFormDialog
import com.blushdesk.app.ui.inventory.StockAdjustDialog
import com.blushdesk.app.ui.order.AddOrderDialog
import com.blushdesk.app.ui.showroom.ShowroomViewModel
import com.blushdesk.app.ui.theme.ShowroomPinkTheme
import com.blushdesk.app.utils.DocumentService
import com.blushdesk.app.utils.DownloadsSaver
import com.blushdesk.app.utils.ExportDocument
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.PhotoStore
import com.blushdesk.app.utils.ReceiptDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The inventory's screens and its hand-offs with the showroom, on a real in-memory database. */
@RunWith(AndroidJUnit4::class)
class InventoryUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var showroomRepo: OfflineShowroomRepository
    private lateinit var inventoryRepo: OfflineInventoryRepository

    private val noPhotos = object : PhotoStore {
        override suspend fun importPhoto(source: Uri): String = error("unused")
        override fun delete(imageUri: String?) = Unit
    }
    private val photoActions = PhotoActions(importPhoto = { null }, discard = {})
    private val noDocuments = object : DocumentService {
        override suspend fun createReceipt(orderId: Long): ReceiptDocument = error("unused")
        override suspend fun createOrderSlip(orderId: Long): ReceiptDocument = error("unused")
        override suspend fun createWorkbook(): ExportDocument = error("unused")
        override suspend fun createBuyerWorkbook(buyerId: Long): ExportDocument = error("unused")
        override suspend fun createProductWorkbook(productId: Long): ExportDocument = error("unused")
        override suspend fun createInventoryWorkbook(): ExportDocument = error("unused")
        override suspend fun saveToDownloads(export: ExportDocument): String = error("unused")
    }

    private val keyboard = Product(id = 4, name = "USB Keyboard", sku = "KB-01", sellingPrice = Money.of("850"), quantity = 2, minimumStock = 1)

    @Before
    fun setUp() {
        db = TestData.inMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        showroomRepo = OfflineShowroomRepository(db, noPhotos)
        inventoryRepo = OfflineInventoryRepository(db, noPhotos)
        runBlocking { showroomRepo.saveOperator(TestData.operator) }
    }

    @After
    fun tearDown() = db.close()

    private fun button(label: String) = compose.onNode(hasText(label, substring = true) and hasClickAction())

    @Test
    fun the_export_button_is_now_inventory_system_and_export_moved_to_the_profile_menu() {
        runBlocking { showroomRepo.saveBuyer(TestData.buyer("Ana Reyes")) }
        val showroom = ShowroomViewModel(showroomRepo, noPhotos, noDocuments)
        val inventory = InventoryViewModel(inventoryRepo, noDocuments, syncNotifications = {})
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backupStore = BackupStore(context)
        val backup = BackupViewModel(BackupService(context, db, DownloadsSaver(context), backupStore), backupStore)
        compose.setContent { ShowroomPinkTheme { AppRoot(showroom, inventory, backup, MutableStateFlow(null), onOpenRequestHandled = {}) } }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Ana Reyes").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithText("Export to Excel", substring = true).assertCountEquals(0)
        button("Inventory System").performClick()
        compose.onNodeWithText("Stock movements").assertIsDisplayed()
        compose.onNodeWithText("No products yet").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back to the showroom").performClick()
        compose.onNodeWithContentDescription("Open menu").performClick() // the operator's avatar
        compose.onNodeWithText("Export to Excel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Export to Excel").assertIsDisplayed() // now the dialog's title
        button("Download to device").assertIsDisplayed()
    }

    @Test
    fun the_product_form_catches_a_duplicate_code_as_it_is_typed() {
        var saved: Pair<Product, Int>? = null
        compose.setContent {
            ShowroomPinkTheme {
                ProductFormDialog(
                    initial = null,
                    categories = listOf("Electronics"),
                    otherSkus = setOf("KB-01"),
                    photoActions = photoActions,
                    onSave = { product, stock -> saved = product to stock },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Product name").performTextInput("Wireless Keyboard")
        compose.onNodeWithText("SKU (optional)").performTextInput("kb-01")
        compose.onNodeWithText("Another product already uses this code").assertIsDisplayed()

        compose.onNode(hasText("Add product") and hasClickAction()).performClick()
        assertNull(saved)

        compose.onNode(hasSetTextAction() and hasText("KB-01")).performTextReplacement("KB-02")
        compose.onNodeWithText("Selling price").performTextInput("1250")
        compose.onNode(hasText("Initial stock") and hasSetTextAction()).performTextReplacement("15")
        compose.onNode(hasText("Add product") and hasClickAction()).performClick()

        val (product, stock) = saved!!
        assertEquals("KB-02", product.sku)
        assertEquals(Money.of("1250"), product.sellingPrice)
        assertEquals(15, stock)
    }

    @Test
    fun the_order_form_offers_the_inventory_and_stops_at_the_stock_on_hand() {
        var saved: List<OrderItem>? = null
        compose.setContent {
            ShowroomPinkTheme {
                AddOrderDialog(buyerId = 7, buyerName = "Ana Reyes", products = listOf(keyboard), onSave = { _: Order, items, _ -> saved = items }, onDismiss = {})
            }
        }
        button("Choose from inventory").performClick()
        compose.onNode(hasText("USB Keyboard", substring = true) and hasClickAction()).performClick()
        compose.onNodeWithText("From inventory", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Increase quantity").performClick()
        compose.onNodeWithContentDescription("Increase quantity").performClick()
        compose.onNodeWithText("Only 2 pc in stock").assertIsDisplayed()
        compose.onNode(hasText("Add order") and hasClickAction()).performClick()
        assertNull(saved)

        compose.onNodeWithContentDescription("Decrease quantity").performClick()
        compose.onNode(hasText("Add order") and hasClickAction()).performClick()
        val line = saved!!.single()
        assertEquals(4L, line.productId)
        assertEquals(2, line.quantity)
        assertEquals("USB Keyboard", line.productName)
        assertEquals(Money.of("850"), line.unitPrice)
    }

    @Test
    fun an_adjustment_shows_the_new_stock_and_never_goes_below_zero() {
        var saved: Pair<Int, AdjustmentReason>? = null
        compose.setContent {
            ShowroomPinkTheme {
                StockAdjustDialog(product = keyboard.copy(quantity = 3), onSave = { change, reason, _ -> saved = change to reason }, onDismiss = {})
            }
        }
        button("Damaged").performClick()
        compose.onNodeWithText("Units to remove").performTextInput("5")
        compose.onNodeWithText("Only 3 pc in stock").assertIsDisplayed()

        compose.onNode(hasSetTextAction() and hasText("5")).performTextReplacement("2")
        compose.onNodeWithText("1 pc").assertIsDisplayed()
        compose.onNode(hasText("Save adjustment") and hasClickAction()).performClick()
        assertEquals(-2 to AdjustmentReason.DAMAGED, saved)
    }

    @Test
    fun adding_a_product_then_stocking_in_shows_the_new_stock() {
        val inventory = InventoryViewModel(inventoryRepo, noDocuments, syncNotifications = {})
        compose.setContent { ShowroomPinkTheme { InventoryScreen(inventory, storeName = "Rosé Showroom", photoActions = photoActions, onBack = {}) } }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("No products yet").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodes(hasText("Add product", substring = true) and hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("Product name").performTextInput("USB Keyboard")
        compose.onNodeWithText("Selling price").performTextInput("850")
        compose.onNode(hasText("Initial stock") and hasSetTextAction()).performTextReplacement("20")
        compose.onNode(hasText("Add product") and hasClickAction()).performClick()

        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("20 pc").fetchSemanticsNodes().isNotEmpty() }
        button("Stock in").performClick()
        compose.onNodeWithText("Quantity received (pc)").performTextInput("10")
        compose.onNode(hasText("Add to stock") and hasClickAction()).performClick()

        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("30 pc").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Stock in", substring = true).onFirst().assertExists()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
