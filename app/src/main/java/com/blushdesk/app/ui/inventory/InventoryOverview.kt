package com.blushdesk.app.ui.inventory

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.ui.components.Avatar
import com.blushdesk.app.ui.components.EmptyState
import com.blushdesk.app.ui.components.StatCard
import com.blushdesk.app.ui.components.StockStatusBadge
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money

/**
 * The inventory at a glance: figures counted in the database, what needs restocking, the latest
 * stock movements, and recently added or changed products. Every row opens its product.
 */
@Composable
fun InventoryOverview(
    state: InventoryUiState,
    compact: Boolean,
    onOpenProduct: (Long) -> Unit,
    onAddProduct: () -> Unit,
    onShowMovements: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loaded && state.products.isEmpty()) {
        Column(modifier = modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                icon = Icons.Filled.Inventory2,
                title = "No products yet",
                message = "Add the products you sell with their stock. Orders then take sold items out of stock automatically.",
                onTint = true,
                action = {
                    Button(onClick = onAddProduct) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                        Text("  Add product")
                    }
                },
            )
        }
        return
    }

    val s = state.summary
    val colors = ShowroomTheme.colors
    val cards = listOf<@Composable (Modifier) -> Unit>(
        { StatCard("Products", s.totalProducts.toString(), Icons.Filled.Inventory2, it) },
        { StatCard("Units in stock", s.totalUnits.toString(), Icons.Filled.Widgets, it, accent = MaterialTheme.colorScheme.tertiary) },
        { StatCard("Stock value", Money.format(s.stockValue), Icons.Filled.Paid, it, accent = colors.tone(PaymentStatus.PAID).foreground) },
        { StatCard("In stock", s.inStock.toString(), Icons.Filled.CheckCircle, it, accent = colors.tone(StockStatus.IN_STOCK).foreground) },
        { StatCard("Low stock", s.lowStock.toString(), Icons.Filled.Warning, it, accent = colors.tone(StockStatus.LOW_STOCK).foreground) },
        { StatCard("Out of stock", s.outOfStock.toString(), Icons.Filled.RemoveShoppingCart, it, accent = colors.tone(StockStatus.OUT_OF_STOCK).foreground) },
        { StatCard("Units sold", s.unitsSold.toString(), Icons.Filled.ShoppingBag, it, accent = MaterialTheme.colorScheme.secondary) },
        { StatCard("Units restocked", s.unitsRestocked.toString(), Icons.Filled.AddBox, it, accent = colors.tone(StockStatus.IN_STOCK).foreground) },
        { StatCard("Unavailable", s.unavailable.toString(), Icons.Filled.DoNotDisturbOn, it, accent = colors.tone(StockStatus.UNAVAILABLE).foreground) },
    )
    val perRow = if (compact) 2 else 3
    val recentlyAdded = state.products.filter { !it.archived }.sortedByDescending { it.createdAt }.take(RECENT)
    val recentlyUpdated = state.products.filter { !it.archived && it.updatedAt != it.createdAt }.sortedByDescending { it.updatedAt }.take(RECENT)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(if (compact) Dimens.spaceL else Dimens.spaceXxl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
    ) {
        item(key = "cards") {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                cards.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) { row.forEach { it(Modifier.weight(1f)) } }
                }
            }
        }
        item(key = "attention") {
            val attention = state.needsAttention
            SectionTitle("Needs restocking (${attention.size})", modifier = Modifier.padding(top = Dimens.spaceS))
        }
        item(key = "attention-list") {
            SectionCard(Modifier.fillMaxWidth()) {
                val attention = state.needsAttention
                if (attention.isEmpty()) {
                    Text(
                        "Every product for sale is above its minimum stock.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimens.spaceM),
                    )
                } else {
                    attention.take(ATTENTION).forEachIndexed { index, product ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ProductLine(product, "Minimum ${product.minimumStock} ${product.unit}") { onOpenProduct(product.id) }
                    }
                }
            }
        }
        item(key = "movements") {
            SectionTitle("Recent stock movements", action = { TextButton(onClick = onShowMovements) { Text("See all") } })
        }
        item(key = "movements-list") {
            SectionCard(Modifier.fillMaxWidth()) {
                val recent = state.movements.take(RECENT_MOVEMENTS)
                if (recent.isEmpty()) {
                    Text(
                        "No stock has moved yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimens.spaceM),
                    )
                }
                recent.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MovementRowItem(row, showProduct = true, modifier = Modifier.clickable { onOpenProduct(row.movement.productId) })
                }
            }
        }
        item(key = "recent") {
            val added: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                    SectionTitle("Recently added")
                    SectionCard(Modifier.fillMaxWidth()) {
                        recentlyAdded.forEachIndexed { index, p ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ProductLine(p, "Added ${Formats.date(p.createdAt)}") { onOpenProduct(p.id) }
                        }
                    }
                }
            }
            val updated: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                    SectionTitle("Recently updated")
                    SectionCard(Modifier.fillMaxWidth()) {
                        if (recentlyUpdated.isEmpty()) {
                            Text(
                                "Nothing changed since the products were added.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Dimens.spaceM),
                            )
                        }
                        recentlyUpdated.forEachIndexed { index, p ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ProductLine(p, "Updated ${Formats.dateTime(p.updatedAt)}") { onOpenProduct(p.id) }
                        }
                    }
                }
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
                    added(Modifier.fillMaxWidth())
                    updated(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
                    added(Modifier.weight(1f))
                    updated(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A compact product row for the overview's lists. */
@Composable
private fun ProductLine(product: Product, caption: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Dimens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Avatar(imageUri = product.imageUri, name = product.name, size = Dimens.avatarSmall)
        Column(modifier = Modifier.weight(1f)) {
            Text(product.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text("${product.quantity} ${product.unit}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        StockStatusBadge(product.stockStatus)
    }
}

private const val RECENT = 5
private const val RECENT_MOVEMENTS = 8
private const val ATTENTION = 10
