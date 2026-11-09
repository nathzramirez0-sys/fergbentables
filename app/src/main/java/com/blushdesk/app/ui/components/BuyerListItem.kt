package com.blushdesk.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.blushdesk.app.data.local.database.BuyerListItem
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money

/**
 * One buyer in the list: photo, name, contact and how many orders are still open; on the right what
 * they owe across all their orders and one payment chip (Paid, Partly paid or Unpaid). Kept to one
 * compact row so about twice as many buyers fit on the tablet. The selected buyer gets the pink
 * container and a rose outline.
 */
@Composable
fun BuyerListItemCard(
    item: BuyerListItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Gray text sits on pink when selected, where the plain muted gray would be too faint.
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
            modifier = Modifier.padding(horizontal = Dimens.spaceM, vertical = Dimens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            Avatar(imageUri = item.buyer.profileImageUri, name = item.buyer.fullName, size = Dimens.avatarSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.buyer.fullName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(Formats.contactLine(item.buyer.contactNumber, item.buyer.facebookName), ordersLabel(item))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.orderCount > 0) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    Text(
                        text = Money.format(item.balance),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (item.balance.signum() == 0) secondaryText else MaterialTheme.colorScheme.onSurface,
                    )
                    PaymentStatusBadge(buyerPaymentStatus(item))
                }
            }
        }
    }
}

/** "2 open", "3 orders" (all delivered), "1 order" or "No orders yet". */
private fun ordersLabel(item: BuyerListItem): String = when {
    item.orderCount == 0 -> "No orders yet"
    item.openOrders > 0 -> "${item.openOrders} open"
    item.orderCount == 1 -> "1 order"
    else -> "${item.orderCount} orders"
}

/**
 * One chip for the whole buyer: Paid when nothing is owed, Partly paid when their latest order has
 * a payment towards it, otherwise Unpaid.
 */
fun buyerPaymentStatus(item: BuyerListItem): PaymentStatus = when {
    item.balance.signum() == 0 -> PaymentStatus.PAID
    item.latestPaymentStatus == PaymentStatus.PENDING -> PaymentStatus.PENDING
    else -> PaymentStatus.UNPAID
}
