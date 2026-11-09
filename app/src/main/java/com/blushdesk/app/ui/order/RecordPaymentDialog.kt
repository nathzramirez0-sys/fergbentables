package com.blushdesk.app.ui.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.domain.model.PaymentMode
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.FormField
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.components.SegmentedChoice
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money
import com.blushdesk.app.utils.Payments
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val AMOUNT_INPUT = Regex("""^\d{0,10}(\.\d{0,2})?$""")

/**
 * Records money received for [order]: a downpayment, an installment or the rest. The amount starts
 * at the full balance (the usual case at the counter) and can't go above it. The note under the
 * fields says what the balance will be afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordPaymentDialog(
    order: OrderWithItems,
    onSave: (amount: BigDecimal, mode: PaymentMode, paidAt: Instant, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val balance = order.balance
    val half = balance.divide(BigDecimal(2), 2, RoundingMode.DOWN)
    var amount by rememberSaveable { mutableStateOf(Money.toInputString(balance)) }
    var mode by rememberSaveable { mutableStateOf(order.order.paymentMode) }
    var paidAtMillis by rememberSaveable { mutableLongStateOf(Instant.now().toEpochMilli()) }
    var note by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    val parsed = Money.parse(amount)
    val problem = Payments.amountProblem(parsed, balance)
    val paidAt = Instant.ofEpochMilli(paidAtMillis)

    FormDialog(
        title = "Record payment",
        confirmLabel = "Save payment",
        onDismiss = onDismiss,
        hasUnsavedChanges = amount != Money.toInputString(balance) || note.isNotBlank(),
        onConfirm = {
            attempted = true
            if (problem == null && parsed != null) onSave(parsed, mode, paidAt, note)
        },
    ) {
        Text(
            "${Formats.orderNumber(order.order)} · ${Formats.itemsSummary(order.items.map { it.productName })}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(Dimens.spaceL), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
                Figure("Order total", Money.format(order.order.totalAmount), Modifier.weight(1f))
                Figure("Paid so far", Money.format(order.paidAmount), Modifier.weight(1f))
                Figure("Balance", Money.format(balance), Modifier.weight(1f))
            }
        }
        FormField(
            value = amount,
            onValueChange = { if (AMOUNT_INPUT.matches(it)) amount = it },
            label = "Amount received",
            prefix = Money.SYMBOL,
            keyboardType = KeyboardType.Decimal,
            error = problem.takeIf { attempted || (parsed != null && parsed > balance) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            QuickAmount("Full balance", selected = parsed == balance) { amount = Money.toInputString(balance) }
            if (half.signum() > 0 && half < balance) {
                QuickAmount("Half · ${Money.format(half)}", selected = parsed == half) { amount = Money.toInputString(half) }
            }
        }
        Text("Paid by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        SegmentedChoice(PaymentMode.entries, mode, { it.label }, { mode = it })
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            OutlinedButton(onClick = { pickingDate = true }) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  " + Formats.date(paidAt, zone))
            }
        }
        FormField(value = note, onValueChange = { note = it }, label = "Note (optional), like \"Second payment\"", maxLength = NOTE_MAX)
        if (problem == null && parsed != null) {
            val after = Payments.balance(balance, parsed)
            InlineMessage(
                if (after.signum() == 0) {
                    "After this payment the balance is ₱0.00. The order becomes Paid and the paid receipt unlocks."
                } else {
                    "After this payment the balance is ${Money.format(after)}."
                },
                isError = false,
            )
        }
    }

    if (pickingDate) {
        val local = paidAt.atZone(zone)
        val state = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        paidAtMillis = date.atTime(local.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = ShowroomTheme.colors.mutedOnTint)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickAmount(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

private const val NOTE_MAX = 80
