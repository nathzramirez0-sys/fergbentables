package com.blushdesk.app.ui.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.domain.model.AdjustmentReason
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.FormField
import com.blushdesk.app.ui.components.SegmentedChoice
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Validation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private val COUNT_INPUT = Regex("""^\d{0,6}$""")

/**
 * Records a delivery: how many arrived, when, from whom, with a reference such as a delivery
 * receipt number. Shows the stock before and after, as the operator types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockInDialog(
    product: Product,
    onSave: (quantity: Int, receivedAt: Instant, supplier: String, reference: String, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var quantity by rememberSaveable { mutableStateOf("") }
    var receivedMillis by rememberSaveable { mutableLongStateOf(Instant.now().toEpochMilli()) }
    var supplier by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    val quantityError = Validation.stockIn(quantity)
    val supplierError = Validation.note(supplier, Validation.MAX_SUPPLIER)
    val referenceError = Validation.note(reference, Validation.MAX_REFERENCE)
    val notesError = Validation.note(notes)
    val valid = listOf(quantityError, supplierError, referenceError, notesError).all { it == null }
    val added = quantity.trim().toIntOrNull()
    val hasChanges = quantity.isNotEmpty() || supplier.isNotEmpty() || reference.isNotEmpty() || notes.isNotEmpty()

    FormDialog(
        title = "Stock in: ${product.name}",
        confirmLabel = "Add to stock",
        onDismiss = onDismiss,
        hasUnsavedChanges = hasChanges,
        onConfirm = {
            attempted = true
            if (valid) onSave(added!!, Instant.ofEpochMilli(receivedMillis), supplier, reference, notes)
        },
    ) {
        FormField(
            value = quantity,
            onValueChange = { if (COUNT_INPUT.matches(it)) quantity = it },
            label = "Quantity received (${product.unit})",
            leadingIcon = Icons.Filled.Numbers,
            keyboardType = KeyboardType.Number,
            error = if (attempted) quantityError else null,
        )
        StockPreview(product, if (quantityError == null) added else null)
        OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
            Text("  Received " + Formats.date(Instant.ofEpochMilli(receivedMillis), zone))
        }
        FormField(
            value = supplier,
            onValueChange = { supplier = it },
            label = "Supplier (optional)",
            leadingIcon = Icons.Filled.LocalShipping,
            error = if (attempted) supplierError else null,
            maxLength = Validation.MAX_SUPPLIER,
        )
        FormField(
            value = reference,
            onValueChange = { reference = it },
            label = "Reference number (optional)",
            leadingIcon = Icons.Filled.Tag,
            capitalization = KeyboardCapitalization.Characters,
            error = if (attempted) referenceError else null,
            maxLength = Validation.MAX_REFERENCE,
        )
        FormField(
            value = notes,
            onValueChange = { notes = it },
            label = "Notes (optional)",
            capitalization = KeyboardCapitalization.Sentences,
            error = if (attempted) notesError else null,
            maxLength = Validation.MAX_NOTE,
            singleLine = false,
        )
        Text(
            "Recorded as a stock movement under your name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickDate) {
        val picked = Instant.ofEpochMilli(receivedMillis).atZone(zone).toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = picked.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now(zone))
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        // Today keeps the current time; an earlier day is dated at noon.
                        receivedMillis = if (date == LocalDate.now(zone)) {
                            Instant.now().toEpochMilli()
                        } else {
                            date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                        }
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/**
 * A manual correction with a reason. A count correction takes the quantity actually counted; the
 * other reasons take how many units to add or remove. The resulting stock is shown before saving,
 * and stock can never go below zero.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StockAdjustDialog(
    product: Product,
    onSave: (change: Int, reason: AdjustmentReason, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf(AdjustmentReason.COUNT_CORRECTION) }
    var amount by rememberSaveable { mutableStateOf("") }
    var removing by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }

    val counting = reason == AdjustmentReason.COUNT_CORRECTION
    val number = amount.trim().toIntOrNull()
    val change: Int? = when {
        number == null -> null
        counting -> number - product.quantity
        reason.adds == true -> number
        reason.adds == false -> -number
        else -> if (removing) -number else number
    }
    val amountError = when {
        amount.isBlank() -> if (counting) "Enter the quantity you counted" else "Enter how many"
        number == null -> "Enter a whole number"
        counting && number == product.quantity -> "That is already the stock; nothing to change"
        counting -> Validation.stockCount(number, "Counted quantity")
        else -> Validation.adjustment(change)
    } ?: if (change != null && product.quantity + change < 0) {
        "Only ${product.quantity} ${product.unit} in stock"
    } else {
        null
    }
    val overdraw = change != null && product.quantity + change < 0
    val notesError = Validation.note(notes) ?: if (reason == AdjustmentReason.OTHER && notes.isBlank()) "Say what the adjustment is for" else null
    val valid = amountError == null && notesError == null && change != null
    val hasChanges = amount.isNotEmpty() || notes.isNotEmpty() || reason != AdjustmentReason.COUNT_CORRECTION

    FormDialog(
        title = "Adjust stock: ${product.name}",
        confirmLabel = "Save adjustment",
        onDismiss = onDismiss,
        hasUnsavedChanges = hasChanges,
        onConfirm = {
            attempted = true
            if (valid) onSave(change, reason, notes)
        },
    ) {
        Text("Reason", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            AdjustmentReason.entries.forEach { option ->
                FilterChip(selected = option == reason, onClick = { reason = option }, label = { Text(option.label) })
            }
        }
        if (!counting && reason.adds == null) {
            SegmentedChoice(listOf(true, false), removing, { if (it) "Remove from stock" else "Add to stock" }, { removing = it })
        }
        FormField(
            value = amount,
            onValueChange = { if (COUNT_INPUT.matches(it)) amount = it },
            label = when {
                counting -> "Quantity counted (${product.unit})"
                reason.adds == true || (reason.adds == null && !removing) -> "Units to add"
                else -> "Units to remove"
            },
            leadingIcon = Icons.Filled.Numbers,
            keyboardType = KeyboardType.Number,
            // Taking more than is in stock shows at once; anything else after Save is pressed.
            error = if (attempted || overdraw) amountError else null,
        )
        StockPreview(product, change?.takeIf { product.quantity + it >= 0 && it != 0 })
        FormField(
            value = notes,
            onValueChange = { notes = it },
            label = if (reason == AdjustmentReason.OTHER) "What it is for" else "Notes (optional)",
            capitalization = KeyboardCapitalization.Sentences,
            error = if (attempted) notesError else null,
            maxLength = Validation.MAX_NOTE,
            singleLine = false,
        )
        Text(
            "Recorded as a stock movement with the reason and your name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "20 → 30 pc", announced as it changes. */
@Composable
private fun StockPreview(product: Product, change: Int?) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.spaceL, vertical = Dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            Text("STOCK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
            Text("${product.quantity}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "becomes", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                text = change?.let { "${product.quantity + it} ${product.unit}" } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
