package com.blushdesk.app.ui.inventory

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.di.AppContainer
import com.blushdesk.app.domain.model.AdjustmentReason
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.domain.repository.InventoryRepository
import com.blushdesk.app.ui.showroom.UiEvent
import com.blushdesk.app.utils.DocumentService
import com.blushdesk.app.utils.ExportDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Runs the Inventory System: lists, filters, the open product, and every inventory action.
 *
 * After anything that changes stock it asks [syncNotifications] to post notifications, so a
 * low-stock alert appears on the phone right away rather than at the next background run.
 * Failures become friendly messages as in the showroom: a [UserFacingException] is shown as
 * written, anything else is logged and replaced by a generic sentence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModel(
    private val repository: InventoryRepository,
    private val documents: DocumentService,
    private val syncNotifications: suspend () -> Unit,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val tab = MutableStateFlow(savedState.get<String>(KEY_TAB)?.let { name -> InventoryTab.entries.firstOrNull { it.name == name } } ?: InventoryTab.OVERVIEW)
    private val selectedProductId = MutableStateFlow(savedState.get<Long>(KEY_PRODUCT))
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ProductFilter.ACTIVE)
    private val sort = MutableStateFlow(ProductSort.NAME)
    private val movementQuery = MutableStateFlow("")
    private val movementFilter = MutableStateFlow(MovementFilter.ALL)
    private val exporting = MutableStateFlow(false)

    init {
        viewModelScope.launch { tab.collect { savedState[KEY_TAB] = it.name } }
        viewModelScope.launch { selectedProductId.collect { savedState[KEY_PRODUCT] = it } }
    }

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private val detail: Flow<ProductDetail?> = selectedProductId.flatMapLatest { id ->
        if (id == null) {
            flowOf(null)
        } else {
            combine(repository.observeProduct(id), repository.observeMovements(id), repository.observeSales(id)) { product, movements, sales ->
                product?.let { ProductDetail(it, movements, sales) }
            }
        }
    }

    private data class Data(
        val products: List<Product>,
        val summary: com.blushdesk.app.data.local.database.InventorySummary,
        val movements: List<com.blushdesk.app.data.local.database.MovementRow>,
        val notifications: List<com.blushdesk.app.data.local.database.InventoryNotification>,
        val unread: Int,
    )

    private data class View(
        val tab: InventoryTab,
        val query: String,
        val filter: ProductFilter,
        val sort: ProductSort,
        val selected: Long?,
    )

    private data class Extra(val movementQuery: String, val movementFilter: MovementFilter, val detail: ProductDetail?, val exporting: Boolean)

    val uiState: StateFlow<InventoryUiState> = combine(
        combine(repository.products, repository.summary, repository.recentMovements(MOVEMENT_HISTORY), repository.notifications, repository.unreadNotifications, ::Data),
        combine(tab, query, filter, sort, selectedProductId, ::View),
        combine(movementQuery, movementFilter, detail, exporting, ::Extra),
    ) { data, view, extra ->
        InventoryUiState(
            loaded = true,
            products = data.products,
            summary = data.summary,
            movements = data.movements,
            notifications = data.notifications,
            unread = data.unread,
            tab = view.tab,
            query = view.query,
            filter = view.filter,
            sort = view.sort,
            movementQuery = extra.movementQuery,
            movementFilter = extra.movementFilter,
            selectedProductId = view.selected,
            detail = extra.detail?.takeIf { it.product.id == view.selected },
            exporting = extra.exporting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InventoryUiState())

    // ---- Navigation and filters -------------------------------------------------------------

    fun selectTab(value: InventoryTab) {
        tab.value = value
    }

    fun selectProduct(productId: Long?) {
        selectedProductId.value = productId
    }

    /** Opens a product's profile from anywhere (overview, notifications): Products tab, product selected. */
    fun openProduct(productId: Long) {
        filter.update { current -> if (current == ProductFilter.ACTIVE || current == ProductFilter.ARCHIVED) current else ProductFilter.ACTIVE }
        query.value = ""
        selectedProductId.value = productId
        tab.value = InventoryTab.PRODUCTS
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun setFilter(value: ProductFilter) {
        filter.value = value
    }

    fun setSort(value: ProductSort) {
        sort.value = value
    }

    fun onMovementQueryChange(value: String) {
        movementQuery.value = value
    }

    fun setMovementFilter(value: MovementFilter) {
        movementFilter.value = value
    }

    // ---- Products ---------------------------------------------------------------------------

    fun saveProduct(product: Product, initialStock: Int) = act("Couldn't save the product. Please try again.") {
        val isNew = product.id == 0L
        val id = repository.saveProduct(product, initialStock)
        if (isNew) openProduct(id)
        emitSuccess(if (isNew) "Product added" else "Product updated")
    }

    fun setAvailability(productId: Long, availability: Availability) = act("Couldn't change the availability. Please try again.") {
        repository.setAvailability(productId, availability)
        emitSuccess("Availability set to ${availability.label}")
    }

    fun setArchived(productId: Long, archived: Boolean) = act("Couldn't update the product. Please try again.") {
        repository.setArchived(productId, archived)
        emitSuccess(if (archived) "Product archived" else "Product restored")
    }

    fun deleteProduct(productId: Long) = act("Couldn't delete the product. Please try again.") {
        repository.deleteProduct(productId)
        if (selectedProductId.value == productId) selectedProductId.value = null
        emitSuccess("Product deleted")
    }

    // ---- Stock ------------------------------------------------------------------------------

    fun stockIn(productId: Long, quantity: Int, receivedAt: Instant, supplier: String, reference: String, notes: String) =
        act("Couldn't record the delivery. Please try again.") {
            repository.stockIn(productId, quantity, receivedAt, supplier, reference, notes)
            emitSuccess("Added $quantity to stock")
            syncNotifications()
        }

    fun adjustStock(productId: Long, change: Int, reason: AdjustmentReason, notes: String) =
        act("Couldn't adjust the stock. Please try again.") {
            repository.adjustStock(productId, change, reason, notes)
            emitSuccess("Stock adjusted by ${if (change > 0) "+$change" else "$change"}")
            syncNotifications()
        }

    // ---- Notifications ----------------------------------------------------------------------

    fun setReminder(productId: Long, interval: ReminderInterval) = act("Couldn't change the reminder. Please try again.") {
        repository.setReminder(productId, interval)
        emitSuccess(if (interval == ReminderInterval.NEVER) "Stock reminder off" else "Stock reminder: ${interval.label.lowercase()}")
        syncNotifications()
    }

    fun markNotificationsRead() = act("Couldn't update the notifications.") { repository.markNotificationsRead() }

    fun clearNotifications() = act("Couldn't clear the notifications.") {
        repository.clearNotifications()
        emitSuccess("Notification history cleared")
    }

    /** Records due reminders and posts pending notifications; called when the module opens. */
    fun refreshNotifications() = act("Couldn't check the reminders.") { syncNotifications() }

    // ---- Exports ----------------------------------------------------------------------------

    fun exportInventory(download: Boolean) = export(download) { documents.createInventoryWorkbook() }

    fun exportProduct(productId: Long, download: Boolean) = export(download) { documents.createProductWorkbook(productId) }

    private fun export(download: Boolean, create: suspend () -> ExportDocument) {
        if (exporting.value) return
        exporting.value = true
        val failure = if (download) "Couldn't save the Excel file to this device. Please try again." else "Couldn't create the Excel file. Please try again."
        act(failure) {
            try {
                val workbook = create()
                if (download) {
                    _events.send(UiEvent.WorkbookSaved(workbook, savedTo = documents.saveToDownloads(workbook)))
                } else {
                    _events.send(UiEvent.ShareWorkbook(workbook))
                }
            } finally {
                exporting.value = false
            }
        }
    }

    /** For the screen to report problems it detects itself (no app to share with, ...). */
    fun reportError(message: String) = emitError(message)

    // ---- Plumbing ---------------------------------------------------------------------------

    private fun emitSuccess(message: String) {
        _events.trySend(UiEvent.Success(message))
    }

    private fun emitError(message: String) {
        _events.trySend(UiEvent.Error(message))
    }

    private fun act(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserFacingException) {
                emitError(e.message ?: failure)
            } catch (e: Exception) {
                Log.e(TAG, failure, e)
                emitError(failure)
            }
        }
    }

    companion object {
        private const val TAG = "InventoryViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val KEY_TAB = "inventoryTab"
        private const val KEY_PRODUCT = "selectedProductId"

        /** How many recent movements the history tab and the overview keep in memory. */
        const val MOVEMENT_HISTORY = 500

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InventoryViewModel(
                    repository = container.inventory,
                    documents = container.documents,
                    syncNotifications = { container.notifier.sync() },
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}
