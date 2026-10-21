package com.blushdesk.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.APP_NAME

/**
 * The full export, now reached from the dashboard: every buyer, order and item, plus the
 * inventory. Nothing to export only when there are neither buyers nor products.
 */
@Composable
fun ExportDialog(
    summary: ShowroomSummary,
    exporting: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    productCount: Int = 0,
) {
    ExportChoiceDialog(
        title = "Export to Excel",
        description = "Creates an .xlsx workbook from everything on this tablet. Download saves it in " +
            "Download/$APP_NAME on this device; Share sends it by email, to Drive or to another app.",
        sheets = listOf(
            "Items" to "Each order's products, with totals",
            "Buyers" to plural(summary.buyerCount, "buyer"),
            "Orders" to plural(summary.orderCount, "order"),
            "Operator" to "Your profile and showroom",
            "Summary" to "Totals and counts by status",
            "Inventory" to plural(productCount, "product"),
            "Stock Movements" to "Every change to the stock",
        ),
        exporting = exporting,
        blockedMessage = if (summary.buyerCount == 0 && productCount == 0) {
            "There is nothing to export yet. Add a buyer or a product first."
        } else {
            null
        },
        onDownload = onDownload,
        onShare = onShare,
        onDismiss = onDismiss,
    )
}

/**
 * Says what an Excel export will contain before it runs, then builds the workbook and either
 * saves it in Downloads on this device or hands it to the share sheet. Shows progress meanwhile;
 * the screen closes this dialog when the workbook is ready. Used for the full export and for the
 * buyer, product and inventory exports, so all of them work the same way.
 */
@Composable
fun ExportChoiceDialog(
    title: String,
    description: String,
    sheets: List<Pair<String, String>>,
    exporting: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    blockedMessage: String? = null,
) {
    val blocked = blockedMessage != null
    // On a phone in landscape the body is taller than the dialog, so it scrolls; the progress line
    // and the warning sit at its end, so bring them into view when they appear.
    val scroll = rememberScrollState()
    LaunchedEffect(exporting, blocked) {
        if (exporting || blocked) {
            withFrameNanos { } // let the new line be laid out first
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !exporting, dismissOnBackPress = !exporting),
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(Icons.Filled.TableChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sheets.forEach { (sheet, contents) -> SheetLine(sheet, contents) }
                when {
                    blockedMessage != null -> InlineMessage(blockedMessage, isError = true)
                    exporting -> Row(
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(Dimens.iconLarge), strokeWidth = Dimens.progressStroke)
                        Text("Creating the workbook…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        // Two buttons, not one Row: the dialog lays its buttons out in a flow row, so on a narrow
        // phone they wrap onto a second line instead of being squeezed.
        confirmButton = {
            val enabled = !exporting && !blocked
            OutlinedButton(onClick = onDownload, enabled = enabled) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  Download to device")
            }
            Button(onClick = onShare, enabled = enabled) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !exporting) { Text("Cancel") }
        },
    )
}

@Composable
private fun SheetLine(sheet: String, contents: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            sheet,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(128.dp),
        )
        Text(contents, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"
