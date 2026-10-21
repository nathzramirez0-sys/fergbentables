package com.blushdesk.app.ui.showroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.blushdesk.app.data.local.database.InventorySummary
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.ui.inventory.InventoryUiState
import com.blushdesk.app.ui.components.AmountText
import com.blushdesk.app.ui.components.OperatorProfileCard
import com.blushdesk.app.ui.components.ShowroomSearchBar
import com.blushdesk.app.ui.components.buyerList
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Money

/**
 * The left pane, the dashboard: the operator profile card, showroom totals, the Inventory and
 * Export tiles, search, quick actions and the buyer list. Everything is one LazyColumn so on a
 * short window the card scrolls away and the list gets the height; on a tall one the search and
 * actions stay pinned ([pinTools]).
 *
 * Its background is plain white (surface) so every gray caption on it keeps full contrast.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MasterPane(
    state: ShowroomUiState,
    onQueryChange: (String) -> Unit,
    onSelectBuyer: (Long) -> Unit,
    onEditProfile: () -> Unit,
    onAddBuyer: () -> Unit,
    onAddOrder: () -> Unit,
    modifier: Modifier = Modifier,
    pinTools: Boolean = true,
    inventory: InventorySummary = InventoryUiState.EMPTY_SUMMARY,
    onOpenInventory: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    val tools: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = Dimens.spaceXs),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            ShowroomSearchBar(state.query, onQueryChange)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Button(onClick = onAddBuyer, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Add buyer", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onAddOrder,
                    enabled = state.detail != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Add order", maxLines = 1)
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(start = Dimens.spaceL, end = Dimens.spaceL, top = Dimens.spaceL, bottom = Dimens.spaceXxl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        item(key = "profile") { OperatorProfileCard(state.operator, onEditProfile) }
        item(key = "summary") {
            SummaryStrip(state.summary, modifier = Modifier.padding(vertical = Dimens.spaceXs))
        }
        item(key = "shortcuts") {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS), modifier = Modifier.padding(bottom = Dimens.spaceXs)) {
                DashboardTile(
                    icon = Icons.Filled.Inventory,
                    title = "Inventory",
                    caption = inventoryCaption(inventory),
                    alert = inventory.lowStock + inventory.outOfStock > 0,
                    onClick = onOpenInventory,
                    modifier = Modifier.weight(1f),
                )
                DashboardTile(
                    icon = Icons.Filled.TableChart,
                    title = "Export",
                    caption = "Excel workbook",
                    alert = false,
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (pinTools) {
            stickyHeader(key = "tools") { tools() }
        } else {
            item(key = "tools") { tools() }
        }
        buyerList(
            buyers = state.buyers,
            loaded = state.buyersLoaded,
            searching = state.query.isNotBlank(),
            selectedBuyerId = state.selectedBuyerId,
            onSelect = onSelectBuyer,
        )
    }
}

/** Buyers, open orders and the amount still to collect, across the whole showroom. */
@Composable
private fun SummaryStrip(summary: ShowroomSummary, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        // Weights follow the caption and value widths ("OPEN ORDERS" is the longest caption).
        MiniStat("Buyers", summary.buyerCount.toString(), Modifier.weight(0.8f))
        MiniStat("Open orders", summary.openOrders.toString(), Modifier.weight(1.25f))
        MiniStat("To collect", Money.format(summary.outstandingAmount), Modifier.weight(1.45f))
    }
}

/** "3 low · 1 out of stock", "12 products in stock" or "No products yet". */
private fun inventoryCaption(s: InventorySummary): String = when {
    s.totalProducts == 0 -> "No products yet"
    s.lowStock + s.outOfStock == 0 -> "${s.totalProducts} product${if (s.totalProducts == 1) "" else "s"} in stock"
    else -> listOfNotNull(
        s.lowStock.takeIf { it > 0 }?.let { "$it low" },
        s.outOfStock.takeIf { it > 0 }?.let { "$it out of stock" },
    ).joinToString(" · ")
}

/** A tappable shortcut on the dashboard: an icon, a title and one line of status. */
@Composable
private fun DashboardTile(
    icon: ImageVector,
    title: String,
    caption: String,
    alert: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val warning = ShowroomTheme.colors.tone(StockStatus.LOW_STOCK)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.spaceM, vertical = Dimens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconLarge))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (alert) warning.foreground else ShowroomTheme.colors.mutedOnTint,
                    fontWeight = if (alert) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.spaceM, vertical = Dimens.spaceS)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AmountText(value, MaterialTheme.typography.titleSmall, MaterialTheme.colorScheme.secondary, minSize = 9.sp)
        }
    }
}
