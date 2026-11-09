package com.blushdesk.app.ui.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.data.local.database.ProductSale
import com.blushdesk.app.domain.model.Availability
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.ui.components.Avatar
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.components.PaymentStatusBadge
import com.blushdesk.app.ui.components.SegmentedChoice
import com.blushdesk.app.ui.components.StatCard
import com.blushdesk.app.ui.components.StatusBadge
import com.blushdesk.app.ui.components.StockStatusBadge
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money

/** Everything the product profile can ask the screen to do. */
class ProductActions(
    val onEdit: () -> Unit,
    val onStockIn: () -> Unit,
    val onAdjust: () -> Unit,
    val onAvailability: (Availability) -> Unit,
    val onReminder: (ReminderInterval) -> Unit,
    val onExport: () -> Unit,
    val onArchive: () -> Unit,
    val onRestore: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * One product's profile: details, stock figures, the stock actions, availability, the stock
 * reminder, and its full history (every stock movement and every order that sold it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductProfile(
    detail: ProductDetail,
    compact: Boolean,
    actions: ProductActions,
    modifier: Modifier = Modifier,
) {
    val product = detail.product
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(if (compact) Dimens.spaceL else Dimens.spaceXxl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
    ) {
        item(key = "header") { ProductHeader(product, canDelete = detail.sales.isEmpty(), actions = actions) }
        if (product.archived) {
            item(key = "archived") {
                InlineMessage("Archived: hidden from new orders. Past orders and this history are kept. Restore it to sell it again.", isError = false)
            }
        }
        item(key = "figures") {
            val colors = ShowroomTheme.colors
            val cards = listOf<@Composable (Modifier) -> Unit>(
                { StatCard("Current stock", "${product.quantity} ${product.unit}", Icons.Filled.Inventory2, it, accent = colors.tone(product.stockStatus).foreground) },
                { StatCard("Minimum stock", "${product.minimumStock} ${product.unit}", Icons.Filled.Warning, it, accent = colors.tone(StockStatus.LOW_STOCK).foreground) },
                { StatCard("Selling price", Money.format(product.sellingPrice), Icons.Filled.Paid, it, accent = MaterialTheme.colorScheme.secondary) },
                { StatCard("Stock value", Money.format(Money.total(product.sellingPrice, product.quantity)), Icons.Filled.Paid, it, accent = MaterialTheme.colorScheme.tertiary) },
            )
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                    cards.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) { pair.forEach { it(Modifier.weight(1f)) } } }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) { cards.forEach { it(Modifier.weight(1f)) } }
            }
        }
        item(key = "stock-actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Button(onClick = actions.onStockIn, enabled = !product.archived) {
                    Icon(Icons.Filled.AddBox, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Stock in")
                }
                OutlinedButton(onClick = actions.onAdjust, enabled = !product.archived) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Adjust stock")
                }
            }
        }
        item(key = "settings") {
            SettingsCard(product, actions)
        }
        item(key = "details") { DetailsCard(product) }

        item(key = "history-title") { SectionTitle("Stock history (${detail.movements.size})") }
        item(key = "history") {
            SectionCard(Modifier.fillMaxWidth()) {
                detail.movements.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MovementRowItem(row, showProduct = false)
                }
            }
        }
        item(key = "sales-title") { SectionTitle("Sales (${detail.sales.size})") }
        if (detail.sales.isEmpty()) {
            item(key = "no-sales") {
                Text(
                    "Not on any order yet. Choose it from the inventory when adding an order and the stock goes down by itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShowroomTheme.colors.mutedOnTint,
                )
            }
        } else {
            item(key = "sales") {
                SectionCard(Modifier.fillMaxWidth()) {
                    detail.sales.forEachIndexed { index, sale ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SaleLine(sale, product.unit)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductHeader(product: Product, canDelete: Boolean, actions: ProductActions) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.spaceXl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
        ) {
            Avatar(
                imageUri = product.imageUri,
                name = product.name,
                size = Dimens.avatarLarge,
                ring = BorderStroke(Dimens.avatarRing, MaterialTheme.colorScheme.primaryContainer),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    listOfNotNull(product.sku?.let { "SKU $it" }, product.category.ifBlank { null }).joinToString(" · ").ifEmpty { "No code or category" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                    StockStatusBadge(product.stockStatus)
                    if (product.availability != Availability.AVAILABLE && !product.archived) {
                        StatusBadge(product.availability.label, ShowroomTheme.colors.neutralBadge)
                    }
                }
                if (product.description.isNotBlank()) {
                    Text(product.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                OutlinedButton(onClick = actions.onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Edit")
                }
                OutlinedButton(onClick = actions.onExport) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Export")
                }
                if (product.archived) {
                    TextButton(onClick = actions.onRestore) {
                        Icon(Icons.Filled.Unarchive, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                        Text("  Restore")
                    }
                } else {
                    TextButton(onClick = actions.onArchive) {
                        Icon(Icons.Filled.Archive, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                        Text("  Archive")
                    }
                }
                if (canDelete) {
                    TextButton(onClick = actions.onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium), tint = MaterialTheme.colorScheme.error)
                        Text("  Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Availability and the "May stock pa" reminder, changed with one tap each. */
@Composable
private fun SettingsCard(product: Product, actions: ProductActions) {
    SectionCard(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = Dimens.spaceS), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            Text("Availability", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            if (product.archived) {
                Text("Restore the product to change its availability.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SegmentedChoice(Availability.entries, product.availability, { it.label }, actions.onAvailability)
                Text(
                    when (product.availability) {
                        Availability.AVAILABLE -> "Can be added to orders while there is stock. With none left it shows as Out of stock."
                        Availability.UNAVAILABLE -> "Kept in the catalog, but can't be added to new orders for now."
                        Availability.DISCONTINUED -> "No longer carried: can't be added to new orders, and no stock reminders."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconMedium))
                Text("Stock reminder (\"May stock pa\")", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            }
            SegmentedChoice(ReminderInterval.entries, product.reminderInterval, { it.shortLabel }, actions.onReminder)
            Text(reminderStatus(product), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Next: Oct 23 · Last: Oct 20", or why no reminder is coming. */
internal fun reminderStatus(product: Product): String {
    val last = product.reminderLastSentAt?.let { "Last sent ${Formats.dateTime(it)}" }
    return when {
        product.reminderInterval == ReminderInterval.NEVER -> "Off. Choose how often to be reminded that this product still has stock."
        product.archived -> "Paused while archived."
        product.availability == Availability.DISCONTINUED -> "Paused: discontinued products get no reminders."
        product.quantity <= 0 -> "Paused while out of stock; it resumes once stock is added."
        else -> listOfNotNull(product.reminderNextAt?.let { "Next ${Formats.dateTime(it)}" }, last).joinToString(" · ")
    }
}

/** Short labels for the segmented control. */
internal val ReminderInterval.shortLabel: String
    get() = when (this) {
        ReminderInterval.EVERY_3_DAYS -> "3 days"
        ReminderInterval.WEEKLY -> "1 week"
        ReminderInterval.MONTHLY -> "1 month"
        ReminderInterval.NEVER -> "Never"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsCard(product: Product) {
    SectionCard(Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.padding(vertical = Dimens.spaceS),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxl),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            Detail("Product ID", product.id.toString())
            Detail("Unit", product.unit)
            Detail("Cost price", product.costPrice?.let(Money::format) ?: "Not set")
            Detail("Availability", product.availability.label)
            Detail("Created", Formats.dateTime(product.createdAt))
            Detail("Last updated", Formats.dateTime(product.updatedAt))
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SaleLine(sale: ProductSale, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${Formats.orderNumber(sale.orderId, sale.numberPrefix)} · ${sale.buyerName}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Formats.dateTime(sale.purchaseDateTime)} · ${sale.quantity} $unit × ${Money.format(sale.unitPrice)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Money.format(sale.lineTotal), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        PaymentStatusBadge(sale.paymentStatus)
    }
}
