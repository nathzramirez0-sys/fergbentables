package com.blushdesk.app.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.ui.components.FieldRow
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.FormField
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.components.PhotoPickerField
import com.blushdesk.app.ui.components.SegmentedChoice
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.Validation
import kotlinx.coroutines.launch

private val PRICE_INPUT = Regex("""^\d{0,10}(\.\d{0,2})?$""")
private val COUNT_INPUT = Regex("""^\d{0,6}$""")

/**
 * Adds a product ([initial] = null) or edits one. A new product asks for its opening stock, which
 * is recorded as its first stock movement. Editing never shows the quantity: stock only changes
 * through Stock in and Adjust stock, so every change leaves a record.
 *
 * [otherSkus] are the codes already used by other products, so a duplicate is caught as it is
 * typed; the repository checks again when saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductFormDialog(
    initial: Product?,
    categories: List<String>,
    otherSkus: Set<String>,
    photoActions: PhotoActions,
    onSave: (Product, initialStock: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var sku by rememberSaveable { mutableStateOf(initial?.sku.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(initial?.category.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial?.description.orEmpty()) }
    var price by rememberSaveable { mutableStateOf(initial?.sellingPrice?.let(Money::toInputString).orEmpty()) }
    var cost by rememberSaveable { mutableStateOf(initial?.costPrice?.let(Money::toInputString).orEmpty()) }
    var stock by rememberSaveable { mutableStateOf("0") }
    var minimum by rememberSaveable { mutableStateOf((initial?.minimumStock ?: DEFAULT_MINIMUM).toString()) }
    var unit by rememberSaveable { mutableStateOf(initial?.unit ?: Product.DEFAULT_UNIT) }
    var availability by rememberSaveable { mutableStateOf(initial?.availability ?: Availability.AVAILABLE) }
    var imageUri by rememberSaveable { mutableStateOf(initial?.imageUri) }
    var stagedPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val normalizedSku = Validation.normalizeSku(sku)
    val errors = mapOf(
        "name" to Validation.product(name),
        "sku" to (Validation.sku(sku) ?: normalizedSku?.takeIf { it in otherSkus }?.let { "Another product already uses this code" }),
        "category" to Validation.category(category),
        "description" to Validation.description(description),
        "price" to Validation.unitPrice(price),
        "cost" to Validation.costPrice(cost),
        "stock" to if (initial == null) Validation.stockCount(stock, "Initial stock") else null,
        "minimum" to Validation.stockCount(minimum, "Minimum stock"),
        "unit" to Validation.unit(unit),
    )
    val valid = errors.values.all { it == null }
    fun error(key: String) = if (attempted) errors[key] else null

    val hasChanges = name != initial?.name.orEmpty() ||
        sku != initial?.sku.orEmpty() ||
        category != initial?.category.orEmpty() ||
        description != initial?.description.orEmpty() ||
        price != initial?.sellingPrice?.let(Money::toInputString).orEmpty() ||
        cost != initial?.costPrice?.let(Money::toInputString).orEmpty() ||
        (initial == null && stock != "0") ||
        minimum != (initial?.minimumStock ?: DEFAULT_MINIMUM).toString() ||
        unit != (initial?.unit ?: Product.DEFAULT_UNIT) ||
        availability != (initial?.availability ?: Availability.AVAILABLE) ||
        imageUri != initial?.imageUri

    fun cancel() {
        photoActions.discard(stagedPhoto)
        onDismiss()
    }

    FormDialog(
        title = if (initial == null) "Add product" else "Edit product",
        confirmLabel = if (initial == null) "Add product" else "Save",
        onDismiss = ::cancel,
        hasUnsavedChanges = hasChanges,
        onConfirm = {
            attempted = true
            if (valid) {
                stagedPhoto = null
                val base = initial ?: Product(name = "", sellingPrice = Money.ZERO)
                onSave(
                    base.copy(
                        name = name,
                        sku = normalizedSku,
                        category = category,
                        description = description,
                        sellingPrice = Money.parse(price)!!,
                        costPrice = if (cost.isBlank()) null else Money.parse(cost),
                        minimumStock = minimum.trim().toInt(),
                        unit = unit,
                        availability = availability,
                        imageUri = imageUri,
                    ),
                    if (initial == null) stock.trim().toInt() else 0,
                )
            }
        },
    ) {
        PhotoPickerField(
            imageUri = imageUri,
            name = name.ifBlank { "Product" },
            onPicked = { uri ->
                scope.launch {
                    val imported = photoActions.importPhoto(uri) ?: return@launch
                    photoActions.discard(stagedPhoto)
                    stagedPhoto = imported
                    imageUri = imported
                }
            },
            onRemove = {
                photoActions.discard(stagedPhoto)
                stagedPhoto = null
                imageUri = null
            },
        )
        FormField(
            value = name,
            onValueChange = { name = it },
            label = "Product name",
            leadingIcon = Icons.Filled.Inventory2,
            error = error("name"),
            maxLength = Validation.MAX_PRODUCT,
        )
        FieldRow(
            { m ->
                FormField(
                    value = sku,
                    onValueChange = { sku = it.uppercase() },
                    label = "SKU (optional)",
                    modifier = m,
                    leadingIcon = Icons.Filled.QrCode2,
                    capitalization = KeyboardCapitalization.Characters,
                    error = error("sku") ?: errors["sku"]?.takeIf { normalizedSku in otherSkus },
                    maxLength = Validation.MAX_SKU,
                )
            },
            { m ->
                FormField(
                    value = category,
                    onValueChange = { category = it },
                    label = "Category (optional)",
                    modifier = m,
                    leadingIcon = Icons.Filled.Category,
                    error = error("category"),
                    maxLength = Validation.MAX_CATEGORY,
                )
            },
        )
        val suggestions = categories.filter { it.isNotBlank() && !it.equals(category.trim(), ignoreCase = true) }.take(MAX_SUGGESTIONS)
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(onClick = { category = suggestion }, label = { Text(suggestion) })
                }
            }
        }
        FormField(
            value = description,
            onValueChange = { description = it },
            label = "Description (optional)",
            capitalization = KeyboardCapitalization.Sentences,
            error = error("description"),
            maxLength = Validation.MAX_DESCRIPTION,
            singleLine = false,
        )
        FieldRow(
            { m ->
                FormField(
                    value = price,
                    onValueChange = { if (PRICE_INPUT.matches(it)) price = it },
                    label = "Selling price",
                    modifier = m,
                    prefix = Money.SYMBOL,
                    keyboardType = KeyboardType.Decimal,
                    error = error("price"),
                )
            },
            { m ->
                FormField(
                    value = cost,
                    onValueChange = { if (PRICE_INPUT.matches(it)) cost = it },
                    label = "Cost price (optional)",
                    modifier = m,
                    prefix = Money.SYMBOL,
                    keyboardType = KeyboardType.Decimal,
                    error = error("cost"),
                )
            },
        )
        val initialStock: @Composable (Modifier) -> Unit = { m ->
            FormField(
                value = stock,
                onValueChange = { if (COUNT_INPUT.matches(it)) stock = it },
                label = "Initial stock",
                modifier = m,
                keyboardType = KeyboardType.Number,
                error = error("stock"),
            )
        }
        val minimumStock: @Composable (Modifier) -> Unit = { m ->
            FormField(
                value = minimum,
                onValueChange = { if (COUNT_INPUT.matches(it)) minimum = it },
                label = "Minimum stock",
                modifier = m,
                leadingIcon = Icons.Filled.Warning,
                keyboardType = KeyboardType.Number,
                error = error("minimum"),
                supportingText = "Low-stock alert at or below this",
            )
        }
        val unitField: @Composable (Modifier) -> Unit = { m ->
            FormField(
                value = unit,
                onValueChange = { unit = it },
                label = "Unit",
                modifier = m,
                leadingIcon = Icons.Filled.Straighten,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
                error = error("unit"),
                maxLength = Validation.MAX_UNIT,
            )
        }
        // Editing has no initial stock: stock only changes through Stock in and Adjust stock.
        if (initial == null) {
            FieldRow(initialStock, minimumStock, unitField, weights = listOf(1f, 1f, UNIT_WEIGHT), minFieldWidth = MIN_STOCK_FIELD)
        } else {
            FieldRow(minimumStock, unitField, weights = listOf(1f, UNIT_WEIGHT), minFieldWidth = MIN_STOCK_FIELD)
        }
        Text("Availability", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        SegmentedChoice(Availability.entries, availability, { it.label }, { availability = it })
        Text(
            text = when {
                initial != null -> "Stock is ${initial.quantity} ${initial.unit}. Change it with Stock in or Adjust stock, " +
                    "so every change is recorded. Added ${Formats.date(initial.createdAt)}."
                else -> "The initial stock is recorded as the product's first stock movement."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (availability != Availability.AVAILABLE) {
            InlineMessage("${availability.label} products can't be added to new orders.", isError = false)
        }
    }
}

private const val DEFAULT_MINIMUM = 2
private const val UNIT_WEIGHT = 0.7f
private val MIN_STOCK_FIELD = 110.dp
private const val MAX_SUGGESTIONS = 8
