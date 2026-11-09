package com.blushdesk.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.blushdesk.app.ui.theme.Dimens

/** A snackbar message that knows whether it reports a success or an error, and whether it can be undone. */
class ShowroomSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
    canUndo: Boolean = false,
) : SnackbarVisuals {
    override val actionLabel: String? = if (canUndo) "Undo" else null
    override val withDismissAction: Boolean = isError
    // An undoable message stays a little longer, so there is time to reach the button.
    override val duration: SnackbarDuration = if (isError || canUndo) SnackbarDuration.Long else SnackbarDuration.Short
}

/** Shows [message], replacing whatever snackbar is currently on screen; [undo] runs if its Undo is tapped. */
suspend fun SnackbarHostState.show(message: String, isError: Boolean, undo: (() -> Unit)? = null) {
    currentSnackbarData?.dismiss()
    val result = showSnackbar(ShowroomSnackbarVisuals(message, isError, canUndo = undo != null))
    if (result == SnackbarResult.ActionPerformed) undo?.invoke()
}

/**
 * Successes appear in the default dark snackbar; errors use the error container colors with an
 * icon and stay until dismissed or timed out (long), so they are hard to miss.
 */
@Composable
fun ShowroomSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val isError = (data.visuals as? ShowroomSnackbarVisuals)?.isError == true
        Snackbar(
            snackbarData = data,
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
            dismissActionContentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/** An icon + short text inside a card or dialog: an error, or a neutral hint (info icon). */
@Composable
fun InlineMessage(message: String, isError: Boolean, modifier: Modifier = Modifier) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Icon(
            if (isError) Icons.Filled.ErrorOutline else Icons.Filled.Info,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(Dimens.iconMedium),
        )
        Text(message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
