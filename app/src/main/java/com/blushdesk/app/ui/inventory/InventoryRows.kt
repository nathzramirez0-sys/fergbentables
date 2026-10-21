package com.blushdesk.app.ui.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.blushdesk.app.data.local.database.MovementRow
import com.blushdesk.app.data.local.database.Product
import com.blushdesk.app.domain.model.MovementType
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.ui.components.Avatar
import com.blushdesk.app.ui.components.StockStatusBadge
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money

/** One product in a list: picture, name, code and category, stock and its status. */
@Composable
fun ProductListItem(
    product: Product,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondaryText = if (selected) ShowroomTheme.colors.mutedOnTint else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) Dimens.borderSelected else Dimens.border,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            Avatar(imageUri = product.imageUri, name = product.name, size = Dimens.avatarSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = listOfNotNull(product.sku, product.category.ifBlank { null }).joinToString(" · ").ifEmpty { "No code" },
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(Money.format(product.sellingPrice), style = MaterialTheme.typography.bodySmall, color = secondaryText)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                Text(
                    "${product.quantity} ${product.unit}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                )
                StockStatusBadge(product.stockStatus)
            }
        }
    }
}

/**
 * One stock movement: what happened, to which product (unless [showProduct] is off, inside a
 * product's own history), when and by whom, the change and the stock before and after.
 */
@Composable
fun MovementRowItem(row: MovementRow, showProduct: Boolean, modifier: Modifier = Modifier) {
    val m = row.movement
    val colors = ShowroomTheme.colors
    val (icon, tone) = when (m.type) {
        MovementType.INITIAL -> Icons.Filled.Inventory2 to colors.tone(StockStatus.ARCHIVED)
        MovementType.STOCK_IN -> Icons.Filled.AddBox to colors.tone(StockStatus.IN_STOCK)
        MovementType.SALE -> Icons.Filled.ShoppingBag to colors.tone(PaymentStatus.UNPAID)
        MovementType.SALE_RETURN -> Icons.AutoMirrored.Filled.Undo to colors.tone(com.blushdesk.app.domain.model.FulfillmentStatus.PROCESSING)
        MovementType.ADJUSTMENT -> Icons.Filled.Tune to colors.tone(StockStatus.LOW_STOCK)
    }
    val detail = listOfNotNull(
        m.type.label,
        m.orderId?.let { "Order ${Formats.orderNumber(it)}" }?.takeIf { m.reason.isBlank() },
        m.reason.ifBlank { null },
        m.supplier.ifBlank { null }?.let { "from $it" },
        m.reference.ifBlank { null }?.let { "ref. $it" },
    ).joinToString(" · ")
    Row(
        modifier = modifier.padding(vertical = Dimens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Box(Modifier.size(Dimens.iconBadge).clip(CircleShape).background(tone.background), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = m.type.label, tint = tone.foreground, modifier = Modifier.size(Dimens.iconLarge))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (showProduct) {
                Text(row.productName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                detail,
                style = if (showProduct) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = if (showProduct) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Formats.dateTime(m.occurredAt)} · by ${m.performedBy.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = when {
                    m.change > 0 -> "+${m.change}"
                    else -> "${m.change}"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    m.change > 0 -> colors.tone(StockStatus.IN_STOCK).foreground
                    m.change < 0 -> colors.tone(StockStatus.OUT_OF_STOCK).foreground
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                "${m.previousQuantity} → ${m.newQuantity} ${row.productUnit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A section title inside the inventory screens. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        action?.invoke()
    }
}

/** A white card with the app's soft outline, holding a section's rows. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = Dimens.spaceL, vertical = Dimens.spaceS)) { content() }
    }
}
