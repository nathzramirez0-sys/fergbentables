package com.blushdesk.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blushdesk.app.ui.theme.Dimens

/**
 * The app's one single-line text field: label, optional leading icon or prefix, and an error line
 * that appears under it. Pass `error = null` to show nothing (callers hide errors until the user
 * has pressed Save once, so an empty form does not open covered in red).
 */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    leadingIcon: ImageVector? = null,
    prefix: String? = null,
    maxLength: Int? = null,
    readOnly: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (maxLength == null || next.length <= maxLength) onValueChange(next) },
        modifier = modifier.fillMaxWidth(),
        readOnly = readOnly,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        isError = error != null,
        supportingText = (error ?: supportingText)?.let { message -> { Text(message) } },
        leadingIcon = leadingIcon?.let { icon -> { Icon(icon, contentDescription = null) } },
        prefix = prefix?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            capitalization = capitalization,
        ),
    )
}

/**
 * Form fields side by side, each given its share of [weights], when every one of them gets at
 * least [minFieldWidth]; otherwise one under another. A dialog on a phone is too narrow for two
 * fields in a row: their labels wrapped a letter or two per line.
 */
@Composable
fun FieldRow(
    vararg fields: @Composable (Modifier) -> Unit,
    weights: List<Float> = List(fields.size) { 1f },
    minFieldWidth: Dp = 200.dp,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gaps = Dimens.spaceM * (fields.size - 1)
        val narrowest = (maxWidth - gaps) * (weights.min() / weights.sum())
        if (narrowest >= minFieldWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM), verticalAlignment = Alignment.Top) {
                fields.forEachIndexed { index, field -> field(Modifier.weight(weights[index])) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                fields.forEach { field -> field(Modifier.fillMaxWidth()) }
            }
        }
    }
}
