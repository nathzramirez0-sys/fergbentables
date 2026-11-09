package com.blushdesk.app.ui.security

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.blushdesk.app.data.security.LockAfter
import com.blushdesk.app.data.security.PinRules
import com.blushdesk.app.data.security.PinState
import com.blushdesk.app.ui.components.FormDialog
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.components.SegmentedChoice

/**
 * Turns the PIN lock on, changes the PIN or how soon it locks, or turns it off. Any change to a
 * lock that is on needs the current PIN first, so a passer-by can't switch it off. The PINs live
 * in memory only, never in saved screen state.
 */
@Composable
fun PinSettingsDialog(
    state: PinState,
    onCheck: (String) -> Boolean,
    onSetPin: (String) -> Unit,
    onLockAfter: (LockAfter) -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var lockAfter by remember { mutableStateOf(state.lockAfter) }
    var attempted by remember { mutableStateOf(false) }
    var currentWrong by remember { mutableStateOf(false) }

    val changingPin = !state.enabled || pin.isNotEmpty() || confirmation.isNotEmpty()
    val pinProblem = if (changingPin) PinRules.problem(pin, confirmation) else null

    fun currentOk(): Boolean {
        if (!state.enabled) return true
        val ok = onCheck(current)
        currentWrong = !ok
        return ok
    }

    FormDialog(
        title = if (state.enabled) "PIN lock" else "Turn on PIN lock",
        confirmLabel = if (state.enabled) "Save" else "Turn on",
        onDismiss = onDismiss,
        hasUnsavedChanges = pin.isNotEmpty() || current.isNotEmpty(),
        onConfirm = {
            attempted = true
            if (pinProblem == null && currentOk()) {
                if (changingPin) onSetPin(pin)
                onLockAfter(lockAfter)
                onDismiss()
            }
        },
    ) {
        InlineMessage(
            "The app asks for this PIN when it starts and after it has been in the background, so buyers' details stay private on the counter.",
            isError = false,
        )
        if (state.enabled) {
            PinField(current, { current = it.filter(Char::isDigit).take(PinRules.MAX_DIGITS) }, "Current PIN", if (currentWrong) "That PIN is not right." else null)
        }
        PinField(
            pin,
            { pin = it.filter(Char::isDigit).take(PinRules.MAX_DIGITS) },
            if (state.enabled) "New PIN (leave empty to keep it)" else "New PIN, ${PinRules.MIN_DIGITS} to ${PinRules.MAX_DIGITS} digits",
            pinProblem.takeIf { attempted },
        )
        PinField(confirmation, { confirmation = it.filter(Char::isDigit).take(PinRules.MAX_DIGITS) }, "Type the new PIN again", null)
        Text("Lock after this long in the background", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        SegmentedChoice(LockAfter.entries, lockAfter, { it.label }, { lockAfter = it })
        if (!state.enabled) {
            InlineMessage(
                "A forgotten PIN can't be recovered. Make a backup first, and keep the PIN somewhere safe.",
                isError = true,
            )
        } else {
            TextButton(
                onClick = { if (currentOk()) { onTurnOff(); onDismiss() } },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Turn off PIN lock") }
        }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, error: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
