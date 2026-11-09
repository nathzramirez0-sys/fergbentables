package com.blushdesk.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import com.blushdesk.app.ui.theme.Dimens

/**
 * The shared frame for every form: title, scrolling body, Cancel and a confirm button. Tapping
 * outside does not dismiss it, because losing a half-typed order to a stray tap is worse than one
 * extra tap on Cancel.
 *
 * Once the form [hasUnsavedChanges], Back and Cancel ask before throwing the input away. Back is
 * the easy one to hit by accident: with a floating keyboard it closes the form instead of the
 * keyboard. An untouched form still closes straight away.
 *
 * A [footer] stays put under the scrolling fields, above the buttons: the order form keeps its
 * total there, so it is in view whichever product is being edited.
 */
@Composable
fun FormDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    hasUnsavedChanges: Boolean = false,
    maxWidth: Dp = Dimens.dialogMaxWidth,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var askToDiscard by rememberSaveable { mutableStateOf(false) }
    val close = { if (hasUnsavedChanges) askToDiscard = true else onDismiss() }

    AlertDialog(
        onDismissRequest = close,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(0.92f),
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = maxWidth <= Dimens.dialogMaxWidth),
        title = { Text(title, color = MaterialTheme.colorScheme.secondary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                    content = content,
                )
                footer?.invoke()
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )

    if (askToDiscard) {
        AlertDialog(
            onDismissRequest = { askToDiscard = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Discard changes?") },
            text = { Text("What you typed in this form will be lost.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = {
                    askToDiscard = false
                    onDismiss()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askToDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/**
 * Asks before anything is deleted; the confirm button is colored as destructive. An optional
 * checkbox ([optionLabel]) lets the operator decide something about the deletion, for example
 * whether an order's items go back in stock.
 */
@Composable
fun DeleteConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Delete",
    optionLabel: String? = null,
    optionChecked: Boolean = false,
    onOptionChange: (Boolean) -> Unit = {},
    optionHint: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Text(message, style = MaterialTheme.typography.bodyLarge)
                if (optionLabel != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = optionChecked, role = Role.Checkbox, onValueChange = onOptionChange),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = optionChecked, onCheckedChange = null)
                        Text(optionLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Dimens.spaceS))
                    }
                    if (optionHint != null) {
                        Text(optionHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A row of mutually exclusive choices (Cash / Online payment, Unpaid / Pending / Paid, ...). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}
