package com.blushdesk.app.ui.order

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.FormField
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.components.PaymentStatusBadge
import com.blushdesk.app.ui.components.SegmentedChoice
import com.blushdesk.app.ui.components.ShowroomSearchBar
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.Payments
import com.blushdesk.app.utils.Validation
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val PRICE_INPUT = Regex("""^\d{0,10}(\.\d{0,2})?$""")

/**
 * One product line as typed: still text, so half-typed values survive until they are valid.
 * [productId] is set when the line was chosen from the inventory; saving then takes its quantity
 * out of stock. A line typed in by hand ([productId] null) is a custom item and touches no stock.
 */
private data class ItemDraft(
    val product: String = "",
    val price: String = "",
    val quantity: String = "1",
    val productId: Long? = null,
) {
    val unitPrice: BigDecimal? get() = Money.parse(price)
    val qty: Int? get() = quantity.trim().toIntOrNull()
    val errors: Triple<String?, String?, String?>
        get() = Triple(Validation.product(product), Validation.unitPrice(price), Validation.quantity(quantity))
    val isValid: Boolean get() = errors.toList().all { it == null }

    /** unit price x quantity, or null while either is incomplete. */
    val lineTotal: BigDecimal? get() {
        val p = unitPrice ?: return null
        val q = qty ?: return null
        return if (isValid) Money.total(p, q) else null
    }

    fun toItem(position: Int) =
        OrderItem(position = position, productName = product, unitPrice = unitPrice!!, quantity = qty!!, productId = productId)

    companion object {
        fun of(item: OrderItem) =
            ItemDraft(item.productName, Money.toInputString(item.unitPrice), item.quantity.toString(), item.productId)

        fun of(product: Product, quantity: String) =
            ItemDraft(product.name, Money.toInputString(product.sellingPrice), quantity, product.id)
    }
}

/** Keeps the product lines across process death: four strings per line (no product id = ""). */
private val ItemDraftsSaver = listSaver<SnapshotStateList<ItemDraft>, String>(
    save = { drafts -> drafts.flatMap { listOf(it.product, it.price, it.quantity, it.productId?.toString().orEmpty()) } },
    restore = { flat -> flat.chunked(4).map { ItemDraft(it[0], it[1], it[2], it[3].toLongOrNull()) }.toMutableStateList() },
)

/**
 * Whether the inventory lines of an order fit the stock. A product can be on several lines, so
 * units are added up per product. What this order already holds (when editing) counts as
 * available, because saving gives it back before taking the new quantity.
 */
private class StockCheck(products: List<Product>, initial: OrderWithItems?, drafts: List<ItemDraft>) {
    private val byId = products.associateBy { it.id }
    private val held = initial?.itemRows.orEmpty().mapNotNull { line -> line.productId?.let { it to line.quantity } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    private val wanted = drafts.mapNotNull { d -> d.productId?.let { it to (d.qty ?: 0) } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }

    fun product(id: Long?): Product? = id?.let(byId::get)

    /** Units of the product this order may have: what is in stock plus what it already holds. */
    fun available(id: Long): Int = (byId[id]?.quantity ?: 0) + (held[id] ?: 0)

    fun error(draft: ItemDraft): String? {
        val id = draft.productId ?: return null
        val product = byId[id] ?: return "This product is no longer in the inventory"
        val want = wanted[id] ?: 0
        val holds = held[id] ?: 0
        return when {
            want > holds && !product.isSellable -> "${product.name} is ${product.stockStatus.label.lowercase()}"
            want > available(id) -> "Only ${available(id)} ${product.unit} in stock"
            else -> null
        }
    }
}

/** Records a new purchase for the selected buyer; [onSave] also gets the amount paid when it was made. */
@Composable
fun AddOrderDialog(
    buyerId: Long,
    buyerName: String,
    products: List<Product>,
    onSave: (Order, List<OrderItem>, BigDecimal) -> Unit,
    onDismiss: () -> Unit,
) = OrderFormDialog(buyerId = buyerId, buyerName = buyerName, initial = null, products = products, onSave = onSave, onDismiss = onDismiss)

/**
 * The order form shared by [AddOrderDialog] ([initial] = null) and [EditOrderDialog]: one or more
 * product lines, then date, time, fulfillment and payment. Every line total (unit price x
 * quantity) and the order total are calculated on every keystroke and never typed in, so the
 * operator can read the total back to the customer before saving.
 *
 * On a tablet each product is one row, and the total, what is paid now and the balance stay pinned
 * under the fields. A new order can take a downpayment ("Paid now"); the payment status follows
 * from it. An edited order keeps its payments, which are recorded on the order card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrderFormDialog(
    buyerId: Long,
    buyerName: String,
    initial: OrderWithItems?,
    products: List<Product>,
    onSave: (Order, List<OrderItem>, BigDecimal) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val details = initial?.order

    val drafts = rememberSaveable(saver = ItemDraftsSaver) {
        (initial?.items?.map(ItemDraft::of) ?: listOf(ItemDraft())).toMutableStateList()
    }
    // The starting values, kept so the form can tell whether anything was changed.
    val startDrafts = remember(initial) { initial?.items?.map(ItemDraft::of) ?: listOf(ItemDraft()) }
    val startMillis = rememberSaveable { (details?.purchaseDateTime ?: Instant.now()).toEpochMilli() }
    var purchasedAtMillis by rememberSaveable { mutableLongStateOf(startMillis) }
    var mode by rememberSaveable { mutableStateOf(details?.paymentMode ?: PaymentMode.CASH) }
    var paidNow by rememberSaveable { mutableStateOf("") }
    var stage by rememberSaveable { mutableStateOf(details?.fulfillmentStatus ?: FulfillmentStatus.PROCESSING) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    // The line just added with "Add another product", until it has been scrolled to and focused.
    // Not saveable on purpose: rotating the tablet should not jump the form again.
    var newLine by remember { mutableStateOf<Int?>(null) }
    // Which line's "Choose from inventory" picker is open.
    var pickingFor by rememberSaveable { mutableStateOf<Int?>(null) }

    val local = Instant.ofEpochMilli(purchasedAtMillis).atZone(zone).toLocalDateTime()
    val stock = StockCheck(products, initial, drafts.toList())
    val sellable = products.filter { it.isSellable }
    val allValid = drafts.all { it.isValid && stock.error(it) == null }
    val orderTotal = if (allValid) drafts.fold(Money.ZERO) { sum, d -> sum + d.lineTotal!! } else null
    // Paid so far: what an edited order already has, or what is typed in for a new one.
    val paidAmount = if (initial != null) initial.paidAmount else Money.parse(paidNow.ifBlank { "0" })
    val paidProblem = when {
        initial != null || paidNow.isBlank() -> null
        paidAmount == null -> "Enter an amount like 5000 or 5000.50"
        orderTotal != null && paidAmount > orderTotal -> "More than the order total of ${Money.format(orderTotal)}"
        else -> null
    }
    val hasChanges = drafts.toList() != startDrafts ||
        purchasedAtMillis != startMillis ||
        mode != (details?.paymentMode ?: PaymentMode.CASH) ||
        paidNow.isNotBlank() ||
        stage != (details?.fulfillmentStatus ?: FulfillmentStatus.PROCESSING)

    FormDialog(
        title = if (initial == null) "New order for $buyerName" else "Edit order",
        confirmLabel = if (initial == null) "Add order" else "Save",
        onDismiss = onDismiss,
        hasUnsavedChanges = hasChanges,
        maxWidth = ORDER_DIALOG_MAX_WIDTH,
        footer = {
            OrderTotalsBar(
                total = orderTotal,
                lines = drafts.size,
                units = drafts.sumOf { it.qty ?: 0 },
                paid = paidAmount?.takeIf { paidProblem == null } ?: Money.ZERO,
                paidLabel = if (initial == null) "Paid now" else "Paid so far",
                problem = if (drafts.any { stock.error(it) != null }) {
                    "Some products need more stock than is on hand"
                } else {
                    "Complete every product's price and quantity"
                },
            )
        },
        onConfirm = {
            attempted = true
            if (allValid && drafts.isNotEmpty() && paidProblem == null) {
                val purchased = Instant.ofEpochMilli(purchasedAtMillis)
                val items = drafts.mapIndexed { index, draft -> draft.toItem(index) }
                // The repository sets the payment status from the payments.
                val order = details?.copy(
                    purchaseDateTime = purchased,
                    paymentMode = mode,
                    fulfillmentStatus = stage,
                ) ?: Order(
                    buyerId = buyerId,
                    purchaseDateTime = purchased,
                    paymentMode = mode,
                    paymentStatus = PaymentStatus.UNPAID,
                    fulfillmentStatus = stage,
                )
                onSave(order, items, if (initial == null) paidAmount ?: Money.ZERO else Money.ZERO)
            }
        },
    ) {
        SectionLabel("Products")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Wide enough for one row per product (a tablet); narrower windows keep a card per product.
            val rows = maxWidth >= ITEM_ROW_MIN_WIDTH
            Column(verticalArrangement = Arrangement.spacedBy(if (rows) Dimens.spaceS else Dimens.spaceM)) {
                drafts.forEachIndexed { index, draft ->
                    val line = ItemLine(
                        index = index,
                        draft = draft,
                        showErrors = attempted,
                        canRemove = drafts.size > 1,
                        isNew = index == newLine,
                        linked = stock.product(draft.productId),
                        available = draft.productId?.let(stock::available),
                        stockError = stock.error(draft),
                        canPick = sellable.isNotEmpty(),
                    )
                    val onChange = { changed: ItemDraft -> drafts[index] = changed }
                    if (rows) {
                        ItemRow(line, onShown = { newLine = null }, onChange = onChange, onRemove = { drafts.removeAt(index) }, onPick = { pickingFor = index })
                    } else {
                        ItemEditor(
                            index = index,
                            draft = draft,
                            showErrors = attempted,
                            canRemove = line.canRemove,
                            isNew = line.isNew,
                            linked = line.linked,
                            available = line.available,
                            stockError = line.stockError,
                            canPick = line.canPick,
                            onShown = { newLine = null },
                            onChange = onChange,
                            onRemove = { drafts.removeAt(index) },
                            onPick = { pickingFor = index },
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                drafts.add(ItemDraft())
                newLine = drafts.lastIndex
            },
            enabled = drafts.size < Validation.MAX_ITEMS,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
            Text(if (drafts.size < Validation.MAX_ITEMS) "  Add another product" else "  ${Validation.MAX_ITEMS} products is the limit")
        }


        SectionLabel("Purchase date and time")
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  " + Formats.date(local))
            }
            OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  " + Formats.time(local))
            }
        }

        SectionLabel("Fulfillment status")
        SegmentedChoice(FulfillmentStatus.entries, stage, { it.label }, { stage = it })

        SectionLabel("Payment")
        if (initial == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM), verticalAlignment = Alignment.Top) {
                FormField(
                    value = paidNow,
                    onValueChange = { if (PRICE_INPUT.matches(it)) paidNow = it },
                    label = "Paid now (downpayment)",
                    modifier = Modifier.weight(1f),
                    prefix = Money.SYMBOL,
                    keyboardType = KeyboardType.Decimal,
                    error = paidProblem,
                )
                Column(Modifier.weight(1f).padding(top = Dimens.spaceS)) {
                    SegmentedChoice(PaymentMode.entries, mode, { it.label }, { mode = it })
                }
            }
            InlineMessage(
                "Enter a downpayment, the full amount, or leave it empty. The payment status follows from it.",
                isError = false,
            )
        } else {
            SegmentedChoice(PaymentMode.entries, mode, { it.label }, { mode = it })
            InlineMessage(
                "Paid so far: ${Money.format(initial.paidAmount)}. Record payments from the order card.",
                isError = false,
            )
        }
    }

    pickingFor?.let { line ->
        ProductPickerDialog(
            products = products.filter { !it.archived },
            available = stock::available,
            onPick = { product ->
                drafts.getOrNull(line)?.let { current -> drafts[line] = ItemDraft.of(product, current.quantity) }
                pickingFor = null
            },
            onDismiss = { pickingFor = null },
        )
    }

    if (showDatePicker) {
        // The Material date picker works in UTC midnights, so convert the local date both ways.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { picked ->
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        purchasedAtMillis = date.atTime(local.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        TimeChooserDialog(
            initial = local.toLocalTime(),
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
            onConfirm = { time ->
                purchasedAtMillis = local.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli()
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
}

/**
 * One product line: name, unit price, quantity stepper and its line total. A line that was just
 * added ([isNew]) scrolls itself into view and puts the cursor in its name, because the new card
 * otherwise appears below the fold and the button seems to do nothing.
 */
@Composable
private fun ItemEditor(
    index: Int,
    draft: ItemDraft,
    showErrors: Boolean,
    canRemove: Boolean,
    isNew: Boolean,
    linked: Product?,
    available: Int?,
    stockError: String?,
    canPick: Boolean,
    onShown: () -> Unit,
    onChange: (ItemDraft) -> Unit,
    onRemove: () -> Unit,
    onPick: () -> Unit,
) {
    val (productError, priceError, qtyError) = draft.errors
    val bringIntoView = remember { BringIntoViewRequester() }
    val nameFocus = remember { FocusRequester() }
    if (isNew) {
        LaunchedEffect(Unit) {
            withFrameNanos { } // the card has to be laid out before it can be scrolled to
            bringIntoView.bringIntoView()
            nameFocus.requestFocus()
            onShown()
        }
    }
    Surface(
        modifier = Modifier.bringIntoViewRequester(bringIntoView),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(start = Dimens.spaceM, end = Dimens.spaceXs, top = Dimens.spaceXs, bottom = Dimens.spaceM),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Product ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = draft.lineTotal?.let { Money.format(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = if (canRemove) 0.dp else Dimens.spaceM),
                )
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove product ${index + 1}", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Column(modifier = Modifier.padding(end = Dimens.spaceS), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                InventoryLink(
                    draft = draft,
                    linked = linked,
                    available = available,
                    canPick = canPick,
                    onPick = onPick,
                    onUnlink = { onChange(draft.copy(productId = null)) },
                )
                FormField(
                    value = draft.product,
                    onValueChange = { onChange(draft.copy(product = it)) },
                    label = if (draft.productId != null) "Product (from inventory)" else "Product name",
                    modifier = Modifier.focusRequester(nameFocus),
                    leadingIcon = Icons.Filled.Inventory2,
                    error = if (showErrors) productError else null,
                    maxLength = Validation.MAX_PRODUCT,
                    readOnly = draft.productId != null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM), verticalAlignment = Alignment.Top) {
                    FormField(
                        value = draft.price,
                        onValueChange = { if (PRICE_INPUT.matches(it)) onChange(draft.copy(price = it)) },
                        label = "Unit price",
                        modifier = Modifier.weight(1f),
                        prefix = Money.SYMBOL,
                        keyboardType = KeyboardType.Decimal,
                        error = if (showErrors) priceError else null,
                    )
                    QuantityField(
                        value = draft.quantity,
                        onValueChange = { text -> if (text.length <= 4 && text.all { it.isDigit() }) onChange(draft.copy(quantity = text)) },
                        // Too few in stock shows at once, not only after Save: the operator is still with the buyer.
                        error = (if (showErrors) qtyError else null) ?: stockError,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Above a line's name: where it comes from. A line from the inventory shows the stock this order
 * can draw on and can be turned back into a custom item; a custom line offers the picker.
 */
@Composable
private fun InventoryLink(
    draft: ItemDraft,
    linked: Product?,
    available: Int?,
    canPick: Boolean,
    onPick: () -> Unit,
    onUnlink: () -> Unit,
) {
    val linkedLine = draft.productId != null
    val label: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            if (linkedLine) {
                Icon(Icons.Filled.Inventory, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconMedium))
            }
            Text(
                text = when {
                    !linkedLine -> "Custom item: no stock is taken"
                    linked != null -> "From inventory · ${available ?: linked.quantity} ${linked.unit} available" + (linked.sku?.let { " · $it" } ?: "")
                    else -> "From inventory"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val actions: @Composable () -> Unit = {
        if (linkedLine) {
            TextButton(onClick = onPick) { Text("Change") }
            TextButton(onClick = onUnlink) { Text("Custom item") }
        } else if (canPick) {
            TextButton(onClick = onPick) {
                Icon(Icons.Filled.Inventory, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  Choose from inventory")
            }
        }
    }
    // Beside the text on a tablet; under it on a phone, where the buttons left the text one letter wide.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= LINK_ROW_MIN_WIDTH) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                label(Modifier.weight(1f))
                actions()
            }
        } else {
            Column {
                label(Modifier)
                Row { actions() }
            }
        }
    }
}

private val LINK_ROW_MIN_WIDTH = 380.dp

/**
 * Picks a product for an order line: search by name, SKU or category, with each product's price
 * and the stock this order can use. Products that can't be sold are shown greyed out with why.
 */
@Composable
private fun ProductPickerDialog(
    products: List<Product>,
    available: (Long) -> Int,
    onPick: (Product) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim().lowercase()
    val shown = products
        .filter { p ->
            needle.isEmpty() || p.name.lowercase().contains(needle) || p.sku.orEmpty().lowercase().contains(needle) ||
                p.category.lowercase().contains(needle)
        }
        .sortedWith(compareBy({ !it.isSellable || available(it.id) <= 0 }, { it.name.lowercase() }))
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.widthIn(max = Dimens.dialogMaxWidth).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Choose from inventory", color = MaterialTheme.colorScheme.secondary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                ShowroomSearchBar(query, { query = it }, placeholder = "Search name, SKU or category")
                if (shown.isEmpty()) {
                    Text("No product matches.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Dimens.spaceM))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    items(shown, key = { it.id }) { product ->
                        val units = available(product.id)
                        val reason = when {
                            !product.isSellable -> product.stockStatus.label
                            units <= 0 -> "Out of stock"
                            else -> null
                        }
                        Surface(
                            onClick = { onPick(product) },
                            enabled = reason == null,
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().alpha(if (reason == null) 1f else 0.5f),
                        ) {
                            Row(
                                modifier = Modifier.padding(Dimens.spaceM),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(product.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(product.sku, product.category.ifBlank { null }, Money.format(product.sellingPrice)).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("$units ${product.unit}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(reason ?: "available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Number field flanked by - and + buttons, for quantities where tapping beats typing. */
@Composable
private fun QuantityField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val current = value.toIntOrNull() ?: 1
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text("Quantity") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        leadingIcon = {
            IconButton(
                onClick = { onValueChange(maxOf(1, current - 1).toString()) },
                enabled = current > 1,
            ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity") }
        },
        trailingIcon = {
            IconButton(
                onClick = { onValueChange(minOf(Validation.MAX_QUANTITY, current + 1).toString()) },
                enabled = current < Validation.MAX_QUANTITY,
            ) { Icon(Icons.Filled.Add, contentDescription = "Increase quantity") }
        },
    )
}

/**
 * Pinned under the form: the live order total (or [problem] while it can't be worked out), what is
 * paid, and the balance with the payment status it leads to. Announced politely by screen readers
 * as it changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderTotalsBar(total: BigDecimal?, lines: Int, units: Int, paid: BigDecimal, paidLabel: String, problem: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = Dimens.spaceL, vertical = Dimens.spaceM),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxl),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("ORDER TOTAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    text = total?.let { Money.format(it) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (total != null) {
                        "${if (lines == 1) "1 product" else "$lines products"} · $units ${if (units == 1) "unit" else "units"}"
                    } else {
                        problem
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ShowroomTheme.colors.mutedOnTint,
                )
            }
            if (total != null) {
                Column {
                    Text(paidLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = ShowroomTheme.colors.mutedOnTint)
                    Text(Money.format(paid), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("BALANCE", style = MaterialTheme.typography.labelSmall, color = ShowroomTheme.colors.mutedOnTint)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                        Text(Money.format(Payments.balance(total, paid)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        PaymentStatusBadge(Payments.status(total, paid))
                    }
                }
            }
        }
    }
}

/** What a product line needs to draw itself, worked out once by the form. */
private class ItemLine(
    val index: Int,
    val draft: ItemDraft,
    val showErrors: Boolean,
    val canRemove: Boolean,
    val isNew: Boolean,
    val linked: Product?,
    val available: Int?,
    val stockError: String?,
    val canPick: Boolean,
)

/**
 * One product as a single row (tablet): name with where it comes from, unit price, quantity
 * stepper, line total and remove. A line just added scrolls into view and takes the cursor.
 */
@Composable
private fun ItemRow(
    line: ItemLine,
    onShown: () -> Unit,
    onChange: (ItemDraft) -> Unit,
    onRemove: () -> Unit,
    onPick: () -> Unit,
) {
    val draft = line.draft
    val (productError, priceError, qtyError) = draft.errors
    val bringIntoView = remember { BringIntoViewRequester() }
    val nameFocus = remember { FocusRequester() }
    if (line.isNew) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            bringIntoView.bringIntoView()
            nameFocus.requestFocus()
            onShown()
        }
    }
    val linkedLine = draft.productId != null
    val source = when {
        !linkedLine -> "Custom item: no stock is taken"
        line.linked != null -> "From inventory · ${line.available ?: line.linked.quantity} ${line.linked.unit} available" + (line.linked.sku?.let { " · $it" } ?: "")
        else -> "From inventory"
    }
    Row(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            FormField(
                value = draft.product,
                onValueChange = { onChange(draft.copy(product = it)) },
                label = "Product name",
                modifier = Modifier.focusRequester(nameFocus),
                leadingIcon = if (linkedLine) Icons.Filled.Inventory else Icons.Filled.Inventory2,
                error = if (line.showErrors) productError else null,
                maxLength = Validation.MAX_PRODUCT,
                readOnly = linkedLine,
                supportingText = source,
            )
            Row {
                if (linkedLine) {
                    TextButton(onClick = onPick) { Text("Change") }
                    TextButton(onClick = { onChange(draft.copy(productId = null)) }) { Text("Custom item") }
                } else if (line.canPick) {
                    TextButton(onClick = onPick) {
                        Icon(Icons.Filled.Inventory, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                        Text("  Choose from inventory")
                    }
                }
            }
        }
        FormField(
            value = draft.price,
            onValueChange = { if (PRICE_INPUT.matches(it)) onChange(draft.copy(price = it)) },
            label = "Unit price",
            modifier = Modifier.width(ROW_PRICE_WIDTH),
            prefix = Money.SYMBOL,
            keyboardType = KeyboardType.Decimal,
            error = if (line.showErrors) priceError else null,
        )
        QuantityField(
            value = draft.quantity,
            onValueChange = { text -> if (text.length <= 4 && text.all { it.isDigit() }) onChange(draft.copy(quantity = text)) },
            error = (if (line.showErrors) qtyError else null) ?: line.stockError,
            modifier = Modifier.width(ROW_QTY_WIDTH),
        )
        Text(
            text = draft.lineTotal?.let { Money.format(it) } ?: "—",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(ROW_TOTAL_WIDTH).padding(top = Dimens.spaceXl),
        )
        if (line.canRemove) {
            IconButton(onClick = onRemove, modifier = Modifier.padding(top = Dimens.spaceXs)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove product ${line.index + 1}", tint = MaterialTheme.colorScheme.error)
            }
        } else {
            Spacer(Modifier.width(Dimens.touchTarget))
        }
    }
}

private val ORDER_DIALOG_MAX_WIDTH = 820.dp
private val ITEM_ROW_MIN_WIDTH = 640.dp
private val ROW_PRICE_WIDTH = 132.dp
private val ROW_QTY_WIDTH = 140.dp
private val ROW_TOTAL_WIDTH = 104.dp

/** Material's TimePicker needs a dialog around it; this one sizes itself to the picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChooserDialog(
    initial: LocalTime,
    is24Hour: Boolean,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(IntrinsicSize.Min),
        ) {
            Column(modifier = Modifier.padding(Dimens.spaceXxl)) {
                Text(
                    "Select time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = Dimens.spaceL),
                )
                TimePicker(state = state)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
                }
            }
        }
    }
}
