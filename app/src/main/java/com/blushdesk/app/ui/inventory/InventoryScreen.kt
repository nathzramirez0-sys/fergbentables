package com.blushdesk.app.ui.inventory

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.ui.components.DeleteConfirmationDialog
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.components.ShowroomSnackbarHost
import com.blushdesk.app.ui.components.show
import com.blushdesk.app.ui.export.ExportChoiceDialog
import com.blushdesk.app.ui.export.ExportSavedDialog
import com.blushdesk.app.ui.export.plural
import com.blushdesk.app.ui.showroom.UiEvent
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.APP_NAME
import com.blushdesk.app.utils.AppFiles
import com.blushdesk.app.utils.ExportDocument
import com.blushdesk.app.utils.Sharing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which inventory dialog is open. Saved by kind and product id, like the showroom's dialogs. */
private sealed interface InventoryDialog {
    data object None : InventoryDialog
    data object AddProduct : InventoryDialog
    data class EditProduct(val productId: Long) : InventoryDialog
    data class StockIn(val productId: Long) : InventoryDialog
    data class Adjust(val productId: Long) : InventoryDialog
    data class Archive(val productId: Long) : InventoryDialog
    data class Delete(val productId: Long) : InventoryDialog
    data class ExportProduct(val productId: Long) : InventoryDialog
    data object ExportInventory : InventoryDialog

    /** Not saved: the workbook is already in Downloads. */
    data class ExportSaved(val export: ExportDocument, val savedTo: String) : InventoryDialog
}

private val InventoryDialogSaver = listSaver<InventoryDialog, Any>(
    save = { dialog ->
        when (dialog) {
            InventoryDialog.None, is InventoryDialog.ExportSaved -> emptyList()
            InventoryDialog.AddProduct -> listOf("add")
            InventoryDialog.ExportInventory -> listOf("exportInventory")
            is InventoryDialog.EditProduct -> listOf("edit", dialog.productId)
            is InventoryDialog.StockIn -> listOf("stockIn", dialog.productId)
            is InventoryDialog.Adjust -> listOf("adjust", dialog.productId)
            is InventoryDialog.Archive -> listOf("archive", dialog.productId)
            is InventoryDialog.Delete -> listOf("delete", dialog.productId)
            is InventoryDialog.ExportProduct -> listOf("exportProduct", dialog.productId)
        }
    },
    restore = { saved ->
        val id = saved.getOrNull(1) as? Long
        when (saved.firstOrNull()) {
            "add" -> InventoryDialog.AddProduct
            "exportInventory" -> InventoryDialog.ExportInventory
            "edit" -> id?.let(InventoryDialog::EditProduct)
            "stockIn" -> id?.let(InventoryDialog::StockIn)
            "adjust" -> id?.let(InventoryDialog::Adjust)
            "archive" -> id?.let(InventoryDialog::Archive)
            "delete" -> id?.let(InventoryDialog::Delete)
            "exportProduct" -> id?.let(InventoryDialog::ExportProduct)
            else -> null
        } ?: InventoryDialog.None
    },
)

/**
 * The Inventory System, opened from the showroom's top bar: Overview, Products (list and product
 * profile), Stock movements and Notifications. [onBack] returns to the showroom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    storeName: String,
    photoActions: PhotoActions,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dialog by rememberSaveable(stateSaver = InventoryDialogSaver) { mutableStateOf<InventoryDialog>(InventoryDialog.None) }

    // Phone notifications: Android 13+ asks once, the first time a reminder is switched on.
    var phoneAllowed by remember { mutableStateOf(canPostNotifications(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { phoneAllowed = canPostNotifications(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        phoneAllowed = canPostNotifications(context)
        if (phoneAllowed) viewModel.refreshNotifications()
    }
    /**
     * Asks for the notification permission. Android stops showing its dialog once the operator
     * has refused it for good; from the Allow button ([fromButton]) the app's notification
     * settings open instead, since nothing else would happen.
     */
    fun askForNotifications(fromButton: Boolean) {
        if (canPostNotifications(context)) return
        val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val refusedForGood = needsGrant && prefs.getBoolean(KEY_ASKED, false) && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        when {
            needsGrant && !refusedForGood -> {
                prefs.edit { putBoolean(KEY_ASKED, true) }
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            fromButton -> context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
    fun setReminder(productId: Long, interval: ReminderInterval) {
        viewModel.setReminder(productId, interval)
        if (interval != ReminderInterval.NEVER) askForNotifications(fromButton = false)
    }

    LaunchedEffect(Unit) { viewModel.refreshNotifications() }

    // Opening the Notifications tab marks its entries read, after a moment to see which were new.
    LaunchedEffect(state.tab, state.unread) {
        if (state.tab == InventoryTab.NOTIFICATIONS && state.unread > 0) {
            delay(MARK_READ_DELAY_MS)
            viewModel.markNotificationsRead()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Success -> scope.launch { snackbar.show(event.message, isError = false) }
                is UiEvent.Error -> scope.launch { snackbar.show(event.message, isError = true) }
                is UiEvent.WorkbookSaved -> dialog = InventoryDialog.ExportSaved(event.export, event.savedTo)
                is UiEvent.ShareWorkbook -> {
                    if (dialog is InventoryDialog.ExportInventory || dialog is InventoryDialog.ExportProduct) dialog = InventoryDialog.None
                    if (!Sharing.share(context, event.export.file, AppFiles.MIME_XLSX, "$APP_NAME ${event.export.displayName}", "Share Excel export")) {
                        viewModel.reportError("No app on this tablet can receive the Excel file.")
                    }
                }
                is UiEvent.ReceiptReady -> Unit
            }
        }
    }

    // A dialog about a product that no longer exists closes itself.
    LaunchedEffect(dialog, state.products, state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        val id = when (val open = dialog) {
            is InventoryDialog.EditProduct -> open.productId
            is InventoryDialog.StockIn -> open.productId
            is InventoryDialog.Adjust -> open.productId
            is InventoryDialog.Archive -> open.productId
            is InventoryDialog.Delete -> open.productId
            is InventoryDialog.ExportProduct -> open.productId
            else -> null
        }
        if (id != null && state.products.none { it.id == id }) dialog = InventoryDialog.None
    }

    val actions = ProductActions(
        onEdit = { state.selectedProductId?.let { dialog = InventoryDialog.EditProduct(it) } },
        onStockIn = { state.selectedProductId?.let { dialog = InventoryDialog.StockIn(it) } },
        onAdjust = { state.selectedProductId?.let { dialog = InventoryDialog.Adjust(it) } },
        onAvailability = { availability -> state.selectedProductId?.let { viewModel.setAvailability(it, availability) } },
        onReminder = { interval -> state.selectedProductId?.let { setReminder(it, interval) } },
        onExport = { state.selectedProductId?.let { dialog = InventoryDialog.ExportProduct(it) } },
        onArchive = { state.selectedProductId?.let { dialog = InventoryDialog.Archive(it) } },
        onRestore = { state.selectedProductId?.let { viewModel.setArchived(it, archived = false) } },
        onDelete = { state.selectedProductId?.let { dialog = InventoryDialog.Delete(it) } },
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < Dimens.dualPaneMinWidth
        val listWidth = when {
            maxWidth >= Dimens.masterPaneWideFrom -> Dimens.masterPaneWide
            maxWidth >= Dimens.masterPaneMediumFrom -> Dimens.masterPaneMedium
            else -> Dimens.masterPaneCompact
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { ShowroomSnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the showroom")
                        }
                    },
                    title = {
                        Column {
                            Text("Inventory System", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
                            if (!compact) {
                                Text(
                                    storeName.ifBlank { APP_NAME },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        Row(modifier = Modifier.padding(end = Dimens.spaceL)) {
                            OutlinedButton(onClick = { dialog = InventoryDialog.ExportInventory }, modifier = Modifier.padding(end = Dimens.spaceS)) {
                                Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                                Text(if (compact) "  Export" else "  Export inventory")
                            }
                            Button(onClick = { dialog = InventoryDialog.AddProduct }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                                Text(if (compact) "  Add" else "  Add product")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = state.tab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    edgePadding = Dimens.spaceL,
                ) {
                    InventoryTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == state.tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = {
                                if (tab == InventoryTab.NOTIFICATIONS && state.unread > 0) {
                                    BadgedBox(badge = { Badge { Text(state.unread.toString()) } }) { Text(tab.label + "   ") }
                                } else {
                                    Text(tab.label)
                                }
                            },
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    val content = Modifier.fillMaxSize()
                    when (state.tab) {
                        InventoryTab.OVERVIEW -> InventoryOverview(
                            state = state,
                            compact = compact,
                            onOpenProduct = viewModel::openProduct,
                            onAddProduct = { dialog = InventoryDialog.AddProduct },
                            onShowMovements = { viewModel.selectTab(InventoryTab.MOVEMENTS) },
                            modifier = content,
                        )
                        InventoryTab.PRODUCTS -> ProductsTab(
                            state = state,
                            compact = compact,
                            listWidth = listWidth,
                            onQueryChange = viewModel::onQueryChange,
                            onFilter = viewModel::setFilter,
                            onSort = viewModel::setSort,
                            onSelect = viewModel::selectProduct,
                            onAddProduct = { dialog = InventoryDialog.AddProduct },
                            actions = actions,
                            modifier = content,
                        )
                        InventoryTab.MOVEMENTS -> MovementsTab(
                            state = state,
                            compact = compact,
                            onQueryChange = viewModel::onMovementQueryChange,
                            onFilter = viewModel::setMovementFilter,
                            onOpenProduct = viewModel::openProduct,
                            modifier = content,
                        )
                        InventoryTab.NOTIFICATIONS -> NotificationsTab(
                            state = state,
                            compact = compact,
                            phoneNotificationsAllowed = phoneAllowed,
                            onAllowPhoneNotifications = { askForNotifications(fromButton = true) },
                            onReminder = ::setReminder,
                            onOpenProduct = viewModel::openProduct,
                            onMarkRead = viewModel::markNotificationsRead,
                            onClear = viewModel::clearNotifications,
                            modifier = content,
                        )
                    }
                }
            }
        }
    }

    val dismiss = { dialog = InventoryDialog.None }
    fun productOf(id: Long) = state.products.firstOrNull { it.id == id }
    when (val open = dialog) {
        InventoryDialog.None -> Unit

        InventoryDialog.AddProduct -> ProductFormDialog(
            initial = null,
            categories = state.categories,
            otherSkus = state.products.mapNotNull { it.sku }.toSet(),
            photoActions = photoActions,
            onSave = { product, stock ->
                viewModel.saveProduct(product, stock)
                dismiss()
            },
            onDismiss = dismiss,
        )

        is InventoryDialog.EditProduct -> productOf(open.productId)?.let { product ->
            ProductFormDialog(
                initial = product,
                categories = state.categories,
                otherSkus = state.products.filter { it.id != product.id }.mapNotNull { it.sku }.toSet(),
                photoActions = photoActions,
                onSave = { edited, _ ->
                    viewModel.saveProduct(edited, 0)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is InventoryDialog.StockIn -> productOf(open.productId)?.let { product ->
            StockInDialog(
                product = product,
                onSave = { quantity, receivedAt, supplier, reference, notes ->
                    viewModel.stockIn(product.id, quantity, receivedAt, supplier, reference, notes)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is InventoryDialog.Adjust -> productOf(open.productId)?.let { product ->
            StockAdjustDialog(
                product = product,
                onSave = { change, reason, notes ->
                    viewModel.adjustStock(product.id, change, reason, notes)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is InventoryDialog.Archive -> productOf(open.productId)?.let { product ->
            AlertDialog(
                onDismissRequest = dismiss,
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.surface,
                icon = { Icon(Icons.Filled.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Archive ${product.name}?") },
                text = {
                    Text(
                        "It leaves the product list and can't be added to new orders. Past orders, receipts and its stock " +
                            "history keep it, and you can restore it from the Archived filter at any time.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.setArchived(product.id, archived = true)
                        dismiss()
                    }) { Text("Archive") }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
            )
        }

        is InventoryDialog.Delete -> productOf(open.productId)?.let { product ->
            DeleteConfirmationDialog(
                title = "Delete ${product.name}?",
                message = "It has never been sold, so it can be removed for good, with its stock history. This cannot be undone. " +
                    "To keep its history, archive it instead.",
                onConfirm = {
                    viewModel.deleteProduct(product.id)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        }

        is InventoryDialog.ExportProduct -> productOf(open.productId)?.let { product ->
            ExportChoiceDialog(
                title = "Export ${product.name}",
                description = "Creates an .xlsx workbook about this product only. Download saves it in Download/$APP_NAME on " +
                    "this device; Share sends it by email, to Drive or to another app.",
                sheets = listOf(
                    "Product" to "Details, stock and status",
                    "Stock Movements" to "Every change to its stock",
                    "Sales" to "Every order that included it",
                ),
                exporting = state.exporting,
                onDownload = { viewModel.exportProduct(product.id, download = true) },
                onShare = { viewModel.exportProduct(product.id, download = false) },
                onDismiss = dismiss,
            )
        }

        InventoryDialog.ExportInventory -> ExportChoiceDialog(
            title = "Export inventory",
            description = "Creates an .xlsx inventory report. Download saves it in Download/$APP_NAME on this device; Share sends " +
                "it by email, to Drive or to another app.",
            sheets = listOf(
                "Inventory" to plural(state.products.size, "product"),
                "Stock Movements" to "Every change to the stock",
                "Summary" to "Stock figures and value",
            ),
            exporting = state.exporting,
            blockedMessage = if (state.loaded && state.products.isEmpty()) "There is nothing to export yet. Add a product first." else null,
            onDownload = { viewModel.exportInventory(download = true) },
            onShare = { viewModel.exportInventory(download = false) },
            onDismiss = dismiss,
        )

        is InventoryDialog.ExportSaved -> ExportSavedDialog(
            displayName = open.export.displayName,
            savedTo = open.savedTo,
            onOpen = {
                if (Sharing.view(context, open.export.file, AppFiles.MIME_XLSX)) {
                    dismiss()
                } else {
                    viewModel.reportError("No app on this tablet can open Excel files. Install Excel or Google Sheets, or use Share.")
                }
            },
            onDismiss = dismiss,
        )
    }
}

/** Whether a phone notification would actually show: permission granted and notifications on. */
private fun canPostNotifications(context: Context): Boolean {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private const val MARK_READ_DELAY_MS = 2_500L
private const val PREFS = "inventory"
private const val KEY_ASKED = "notificationPermissionAsked"
