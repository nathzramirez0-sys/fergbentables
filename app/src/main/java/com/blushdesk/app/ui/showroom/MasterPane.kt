package com.blushdesk.app.ui.showroom

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.blushdesk.app.data.local.database.InventorySummary
import com.blushdesk.app.data.local.database.ShowroomSummary
import com.blushdesk.app.ui.components.AmountText
import com.blushdesk.app.ui.components.ShowroomSearchBar
import com.blushdesk.app.ui.components.buyerList
import com.blushdesk.app.ui.inventory.InventoryUiState
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Money

/**
 * The left pane: showroom figures in one strip, search, filters (all buyers, those with a balance,
 * those with something to deliver), the buyer list with its sort switch, and Add buyer at the
 * bottom. The operator's profile and the Excel export live in the top bar's menu, which leaves
 * the height to the buyers.
 *
 * Its background is plain white (surface) so every gray caption on it keeps full contrast.
 */
@Composable
fun MasterPane(
    state: ShowroomUiState,
    onQueryChange: (String) -> Unit,
    onSelectBuyer: (Long) -> Unit,
    onAddBuyer: () -> Unit,
    modifier: Modifier = Modifier,
    inventory: InventorySummary = InventoryUiState.EMPTY_SUMMARY,
    onOpenInventory: () -> Unit = {},
) {
    var filter by rememberSaveable { mutableStateOf(BuyerFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(BuyerSort.NAME) }
    val shown = remember(state.buyers, filter, sort) { state.buyers.filteredAndSorted(filter, sort) }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(start = Dimens.spaceL, end = Dimens.spaceL, top = Dimens.spaceL, bottom = Dimens.spaceS),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            SummaryStrip(state.summary, inventory, onOpenInventory)
            ShowroomSearchBar(state.query, onQueryChange)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
            ) {
                BuyerFilter.entries.forEach { option ->
                    val selected = option == filter
                    FilterChip(
                        selected = selected,
                        onClick = { filter = option },
                        label = { Text("${option.label} · ${state.buyers.count(option::matches)}") },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = Dimens.spaceL, end = Dimens.spaceL, bottom = Dimens.spaceL),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
        ) {
            buyerList(
                buyers = shown,
                loaded = state.buyersLoaded,
                searching = state.query.isNotBlank(),
                selectedBuyerId = state.selectedBuyerId,
                onSelect = onSelectBuyer,
                filterMessage = filter.emptyMessage.takeIf { filter != BuyerFilter.ALL && state.buyers.isNotEmpty() },
                headerAction = {
                    TextButton(onClick = { sort = if (sort == BuyerSort.NAME) BuyerSort.MOST_DUE else BuyerSort.NAME }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                        Text("  Sort: ${sort.label}")
                    }
                },
            )
        }
        Button(
            onClick = onAddBuyer,
            modifier = Modifier.fillMaxWidth().padding(start = Dimens.spaceL, end = Dimens.spaceL, bottom = Dimens.spaceL),
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
            Text("  Add buyer", maxLines = 1)
        }
    }
}

/**
 * Open orders, the amount still to collect and the stock that needs attention, across the whole
 * showroom, in one tinted strip. The stock figure opens the Inventory System.
 */
@Composable
private fun SummaryStrip(summary: ShowroomSummary, inventory: InventorySummary, onOpenInventory: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Figure("Open orders", summary.openOrders.toString(), Modifier.weight(1.2f))
            Figure("To collect", Money.format(summary.outstandingAmount), Modifier.weight(1.25f))
            val alert = inventory.lowStock + inventory.outOfStock > 0
            Surface(
                onClick = onOpenInventory,
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Figure(
                    "Stock",
                    stockCaption(inventory),
                    valueColor = if (alert) ShowroomTheme.colors.warning.foreground else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

/** "1 low · 1 out", "All in stock" or "No products". */
private fun stockCaption(s: InventorySummary): String = when {
    s.totalProducts == 0 -> "No products"
    s.lowStock + s.outOfStock == 0 -> "All in stock"
    else -> listOfNotNull(
        s.lowStock.takeIf { it > 0 }?.let { "$it low" },
        s.outOfStock.takeIf { it > 0 }?.let { "$it out" },
    ).joinToString(" · ")
}

@Composable
private fun Figure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.secondary,
) {
    Column(modifier = modifier.padding(horizontal = Dimens.spaceS + Dimens.spaceXs, vertical = Dimens.spaceS)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = ShowroomTheme.colors.mutedOnTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AmountText(value, MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), valueColor, minSize = 9.sp)
    }
}
