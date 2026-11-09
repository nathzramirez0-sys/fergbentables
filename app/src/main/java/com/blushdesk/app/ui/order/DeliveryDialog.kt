package com.blushdesk.app.ui.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.FormField
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.Formats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Where and when [order] is delivered. Both are optional; the date is kept as the start of that
 * day in the tablet's time zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryDialog(
    order: OrderWithItems,
    onSave: (address: String, date: Instant?) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val startDate = order.order.deliveryDate?.atZone(zone)?.toLocalDate()
    var address by rememberSaveable { mutableStateOf(order.order.deliveryAddress) }
    var dateEpochDay by rememberSaveable { mutableStateOf(startDate?.toEpochDay()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val date = dateEpochDay?.let(LocalDate::ofEpochDay)

    FormDialog(
        title = "Delivery details",
        confirmLabel = "Save",
        onDismiss = onDismiss,
        hasUnsavedChanges = address != order.order.deliveryAddress || date != startDate,
        onConfirm = { onSave(address, date?.atStartOfDay(zone)?.toInstant()) },
    ) {
        FormField(
            value = address,
            onValueChange = { address = it },
            label = "Delivery address",
            singleLine = false,
            capitalization = KeyboardCapitalization.Words,
            maxLength = ADDRESS_MAX,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            OutlinedButton(onClick = { pickingDate = true }) {
                Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  " + (date?.let { Formats.date(it.atStartOfDay()) } ?: "Pick a delivery date"))
            }
            if (date != null) TextButton(onClick = { dateEpochDay = null }) { Text("Clear date") }
        }
        InlineMessage("Orders with a delivery date show on the Today view until they are delivered.", isError = false)
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked -> dateEpochDay = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

private const val ADDRESS_MAX = 200
