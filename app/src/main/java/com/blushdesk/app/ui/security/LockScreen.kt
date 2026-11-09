package com.blushdesk.app.ui.security

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blushdesk.app.data.security.PinState
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.APP_NAME
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Covers the app until the PIN is typed. The PIN is checked as soon as its last digit is in; a
 * wrong one clears the dots. After too many wrong tries a countdown shows before the next.
 */
@Composable
fun LockScreen(state: PinState, onUnlock: (String) -> Boolean) {
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val waitSeconds = state.retryAt?.let { Duration.between(Instant.ofEpochMilli(now), it).seconds.coerceAtLeast(0) } ?: 0
    LaunchedEffect(state.retryAt) {
        while (state.retryAt != null && Instant.now().isBefore(state.retryAt)) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }

    fun press(digit: Char) {
        if (waitSeconds > 0 || typed.length >= state.length) return
        wrong = false
        typed += digit
        if (typed.length == state.length) {
            if (!onUnlock(typed)) wrong = true
            typed = ""
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(Dimens.spaceXxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(Dimens.spaceL))
            }
            Text(APP_NAME, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
            Text("Enter your PIN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                modifier = Modifier.semantics { contentDescription = "${typed.length} of ${state.length} digits entered" },
            ) {
                repeat(state.length) { index ->
                    val filled = index < typed.length
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            val message = when {
                waitSeconds > 0 -> "Too many wrong tries. Try again in $waitSeconds s."
                wrong -> "That PIN is not right. Try again."
                else -> ""
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Keypad(enabled = waitSeconds == 0L, onDigit = ::press, onDelete = { typed = typed.dropLast(1) })
            Text(
                "Forgot your PIN? It can't be recovered. Clearing the app's data removes it with the records, " +
                    "which you can then bring back from a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
                row.forEach { digit -> Key(digit.toString(), enabled) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
            Spacer(Modifier.size(KEY_SIZE))
            Key("0", enabled) { onDigit('0') }
            Surface(
                onClick = onDelete,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(KEY_SIZE).semantics { contentDescription = "Delete last digit" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(KEY_SIZE),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private val KEY_SIZE = 64.dp
