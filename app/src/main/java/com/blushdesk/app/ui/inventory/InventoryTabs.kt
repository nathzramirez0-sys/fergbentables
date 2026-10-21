package com.blushdesk.app.ui.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.blushdesk.app.data.local.database.InventoryNotification
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.domain.model.NotificationKind
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.ReminderInterval
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.ui.components.EmptyState
import com.blushdesk.app.ui.components.ShowroomSearchBar
import com.blushdesk.app.ui.components.StatusBadge
import com.blushdesk.app.ui.components.StockStatusBadge
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats

// ---- Products -----------------------------------------------------------------------------------

/**
 * The product list (search, status filter, sort) beside the selected product's profile. On a
 * narrow window the two take turns, with a back row above the profile.
 */
@Composable
fun ProductsTab(
    state: InventoryUiState,
    compact: Boolean,
    listWidth: Dp,
    onQueryChange: (String) -> Unit,
    onFilter: (ProductFilter) -> Unit,
    onSort: (ProductSort) -> Unit,
    onSelect: (Long?) -> Unit,
    onAddProduct: () -> Unit,
    actions: ProductActions,
    modifier: Modifier = Modifier,
) {
    val list: @Composable (Modifier) -> Unit = { m -> ProductListPane(state, onQueryChange, onFilter, onSort, onSelect, onAddProduct, m) }
    val profile: @Composable (Modifier) -> Unit = { m ->
        val detail = state.detail
        if (detail == null) {
            Box(m, contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.TouchApp,
                    title = "Select a product",
                    message = "Choose a product to see its stock, history and sales, and to restock or adjust it.",
                    onTint = true,
                )
            }
        } else {
            ProductProfile(detail, compact, actions, m)
        }
    }

    when {
        compact && state.selectedProductId != null -> Column(modifier) {
            TextButton(onClick = { onSelect(null) }, modifier = Modifier.padding(start = Dimens.spaceS)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  All products")
            }
            profile(Modifier.weight(1f).fillMaxWidth())
        }
        compact -> list(modifier)
        else -> Row(modifier) {
            list(Modifier.width(listWidth).fillMaxHeight())
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            profile(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductListPane(
    state: InventoryUiState,
    onQueryChange: (String) -> Unit,
    onFilter: (ProductFilter) -> Unit,
    onSort: (ProductSort) -> Unit,
    onSelect: (Long?) -> Unit,
    onAddProduct: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    val visible = state.visibleProducts
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(Dimens.spaceL),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        item(key = "search") { ShowroomSearchBar(state.query, onQueryChange, placeholder = "Search name, SKU or category") }
        item(key = "filters") {
            // Wrapped, not scrolled: in the narrow list pane a scrolling row hid Unavailable and Archived.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                ProductFilter.entries.forEach { option ->
                    FilterChip(selected = option == state.filter, onClick = { onFilter(option) }, label = { Text(option.label) })
                }
            }
        }
        item(key = "sort") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${visible.size} of ${state.products.size} product${if (state.products.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { sortOpen = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                        Text("  ${state.sort.label}")
                    }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        ProductSort.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                onSort(option)
                                sortOpen = false
                            })
                        }
                    }
                }
            }
        }
        when {
            !state.loaded -> Unit
            state.products.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = Icons.Filled.Inventory2,
                    title = "No products yet",
                    message = "Add your first product and its stock.",
                    action = {
                        Button(onClick = onAddProduct) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                            Text("  Add product")
                        }
                    },
                )
            }
            visible.isEmpty() -> item(key = "none") {
                EmptyState(icon = Icons.Filled.SearchOff, title = "No matches", message = "No product matches this search and filter.")
            }
            else -> items(visible, key = { it.id }) { product ->
                ProductListItem(product, selected = product.id == state.selectedProductId, onClick = { onSelect(product.id) })
            }
        }
    }
}

// ---- Stock movements ----------------------------------------------------------------------------

/** The whole audit trail, newest first, filtered by kind or product. */
@Composable
fun MovementsTab(
    state: InventoryUiState,
    compact: Boolean,
    onQueryChange: (String) -> Unit,
    onFilter: (MovementFilter) -> Unit,
    onOpenProduct: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.visibleMovements
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(if (compact) Dimens.spaceL else Dimens.spaceXxl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        item(key = "search") { ShowroomSearchBar(state.movementQuery, onQueryChange, placeholder = "Search product name or SKU") }
        item(key = "filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                items(MovementFilter.entries, key = { it.name }) { option ->
                    FilterChip(selected = option == state.movementFilter, onClick = { onFilter(option) }, label = { Text(option.label) })
                }
            }
        }
        item(key = "note") {
            Text(
                "Every change to the stock: deliveries, orders (taken automatically when saved, returned when edited or deleted) " +
                    "and manual adjustments, with who made it. Showing the latest ${InventoryViewModel.MOVEMENT_HISTORY}; the Excel export has all of them.",
                style = MaterialTheme.typography.bodySmall,
                color = ShowroomTheme.colors.mutedOnTint,
            )
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = if (state.movements.isEmpty()) "No stock movements yet" else "No matches",
                    message = if (state.movements.isEmpty()) "Adding products, deliveries and orders will show up here." else "Nothing matches this search and filter.",
                    onTint = true,
                )
            }
        } else {
            item(key = "list") {
                SectionCard(Modifier.fillMaxWidth()) {
                    visible.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        MovementRowItem(row, showProduct = true, modifier = Modifier.clickable { onOpenProduct(row.movement.productId) })
                    }
                }
            }
        }
    }
}

// ---- Notifications ------------------------------------------------------------------------------

/**
 * The reminder settings of every product that can have one, and the notification history (stock
 * reminders and low-stock alerts, kept apart by their badge). Explains when phone notifications
 * are switched off.
 */
@Composable
fun NotificationsTab(
    state: InventoryUiState,
    compact: Boolean,
    phoneNotificationsAllowed: Boolean,
    onAllowPhoneNotifications: () -> Unit,
    onReminder: (Long, ReminderInterval) -> Unit,
    onOpenProduct: (Long) -> Unit,
    onMarkRead: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reminderProducts = state.products.filter { !it.archived }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(if (compact) Dimens.spaceL else Dimens.spaceXxl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
    ) {
        if (!phoneNotificationsAllowed) {
            item(key = "permission") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(
                        modifier = Modifier.padding(Dimens.spaceL),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                    ) {
                        Icon(Icons.Filled.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "Phone notifications are off. Reminders and alerts still appear here; allow notifications to get them while the app is closed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = onAllowPhoneNotifications) { Text("Allow") }
                    }
                }
            }
        }
        // The history comes first: it is what a tapped phone notification opens this tab to see.
        item(key = "history-title") {
            SectionTitle(
                "Notification history",
                action = {
                    Row {
                        TextButton(onClick = onMarkRead, enabled = state.unread > 0) {
                            Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                            Text("  Mark all read")
                        }
                        TextButton(onClick = onClear, enabled = state.notifications.isNotEmpty()) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                            Text("  Clear")
                        }
                    }
                },
            )
        }
        item(key = "history") {
            SectionCard(Modifier.fillMaxWidth()) {
                if (state.notifications.isEmpty()) {
                    Text(
                        "No notifications yet. Stock reminders and low-stock alerts will be listed here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimens.spaceM),
                    )
                }
                state.notifications.forEachIndexed { index, n ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NotificationLine(n, onClick = { onOpenProduct(n.productId) })
                }
            }
        }
        item(key = "settings-title") { SectionTitle("Stock reminders") }
        item(key = "settings-note") {
            Text(
                "\"May stock pa\": a reminder that a product still has stock, as often as you choose. It pauses while the product " +
                    "is out of stock, archived or discontinued. Low-stock alerts are separate and always on.",
                style = MaterialTheme.typography.bodyMedium,
                color = ShowroomTheme.colors.mutedOnTint,
            )
        }
        item(key = "settings") {
            SectionCard(Modifier.fillMaxWidth()) {
                if (reminderProducts.isEmpty()) {
                    Text("No products yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = Dimens.spaceM))
                }
                reminderProducts.forEachIndexed { index, product ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ReminderRow(product, compact, onReminder = { onReminder(product.id, it) }, onOpen = { onOpenProduct(product.id) })
                }
            }
        }
    }
}

@Composable
private fun ReminderRow(product: Product, compact: Boolean, onReminder: (ReminderInterval) -> Unit, onOpen: () -> Unit) {
    val info: @Composable (Modifier) -> Unit = { m ->
        Column(m.clickable(onClick = onOpen)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Text(product.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                StockStatusBadge(product.stockStatus)
            }
            Text(
                "${product.quantity} ${product.unit} in stock · ${reminderStatus(product)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val chips: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            ReminderInterval.entries.forEach { option ->
                FilterChip(selected = option == product.reminderInterval, onClick = { onReminder(option) }, label = { Text(option.shortLabel) })
            }
        }
    }
    if (compact) {
        Column(Modifier.padding(vertical = Dimens.spaceS), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            info(Modifier.fillMaxWidth())
            chips()
        }
    } else {
        Row(
            modifier = Modifier.padding(vertical = Dimens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL),
        ) {
            info(Modifier.weight(1f))
            chips()
        }
    }
}

@Composable
private fun NotificationLine(n: InventoryNotification, onClick: () -> Unit) {
    val colors = ShowroomTheme.colors
    val tone = when (n.kind) {
        NotificationKind.REMAINING_STOCK -> colors.tone(PaymentStatus.PAID)
        NotificationKind.LOW_STOCK -> colors.tone(StockStatus.LOW_STOCK)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Dimens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Icon(
            if (n.kind == NotificationKind.LOW_STOCK) Icons.Filled.Warning else Icons.Filled.NotificationsActive,
            contentDescription = null,
            tint = tone.foreground,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                n.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (n.readAt == null) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(n.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Formats.dateTime(n.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusBadge(n.kind.label, tone)
        if (n.readAt == null) {
            Box(Modifier.size(Dimens.spaceS).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
        }
    }
}
