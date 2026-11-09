package com.blushdesk.app.ui.showroom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blushdesk.app.data.backup.BackupStatus
import com.blushdesk.app.data.local.database.OpenOrder
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.ui.components.FulfillmentStatusBadge
import com.blushdesk.app.ui.components.PaymentStatusBadge
import com.blushdesk.app.ui.components.StockStatusBadge
import com.blushdesk.app.ui.components.buyerPaymentStatus
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today: what the counter has to get done. Orders still to prepare or deliver (soonest first),
 * who still owes money, and which products are running low; plus a nudge when a backup is due.
 * Every row opens the buyer or the inventory it is about.
 */
@Composable
fun TodayScreen(
    state: ShowroomUiState,
    backup: BackupStatus,
    onOpenOrder: (buyerId: Long, orderId: Long) -> Unit,
    onOpenBuyer: (Long) -> Unit,
    onOpenInventory: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(backup) { Instant.now() }
    val today = LocalDate.now(zone)
    val owing = remember(state.allBuyers) { state.allBuyers.filter { it.balance.signum() > 0 }.sortedByDescending { it.balance } }
    val toCollect = owing.fold(Money.ZERO) { sum, buyer -> sum + buyer.balance }
    val low = remember(state.products) {
        state.products
            .filter { it.stockStatus == StockStatus.OUT_OF_STOCK || it.stockStatus == StockStatus.LOW_STOCK }
            .sortedWith(compareBy({ it.stockStatus != StockStatus.OUT_OF_STOCK }, { it.name.lowercase() }))
    }

    Column(
        modifier = modifier.padding(horizontal = Dimens.spaceXxl, vertical = Dimens.spaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
            Text(
                "Today",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }

        val prepare: @Composable (Modifier) -> Unit = { m ->
            TodayCard("To prepare or deliver", state.openOrders.size.toString(), Icons.Filled.LocalShipping, ShowroomTheme.colors.tone(FulfillmentStatus.PROCESSING).background, m) {
                if (state.openOrders.isEmpty()) Quiet("Nothing waiting. Every order is delivered.")
                state.openOrders.forEach { order -> OpenOrderRow(order, today, zone, onClick = { onOpenOrder(order.order.buyerId, order.order.id) }) }
            }
        }
        val collect: @Composable (Modifier) -> Unit = { m ->
            TodayCard("To collect", Money.format(toCollect), Icons.Filled.Payments, MaterialTheme.colorScheme.primaryContainer, m) {
                if (owing.isEmpty()) Quiet("Nobody owes anything. Every order is paid.")
                owing.forEach { buyer ->
                    TodayRow(onClick = { onOpenBuyer(buyer.buyer.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(buyer.buyer.fullName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Money.format(buyer.balance), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                            Text(
                                if (buyer.openOrders == 1) "1 open order" else "${buyer.openOrders} open orders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PaymentStatusBadge(buyerPaymentStatus(buyer))
                        }
                    }
                }
            }
        }
        val stock: @Composable (Modifier) -> Unit = { m ->
            TodayCard("Running low", low.size.toString(), Icons.Filled.Warning, ShowroomTheme.colors.warning.background, m) {
                if (low.isEmpty()) Quiet(if (state.products.isEmpty()) "No products added yet." else "Every product is in stock.")
                low.forEach { product ->
                    TodayRow(onClick = onOpenInventory) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(product.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            StockStatusBadge(product.stockStatus)
                        }
                        Text(
                            "${product.quantity} ${product.unit} left · minimum ${product.minimumStock}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = onOpenInventory) {
                    Icon(Icons.Filled.Inventory, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Open Inventory")
                }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= WIDE_FROM) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXl)) {
                    prepare(Modifier.weight(1f).fillMaxHeight())
                    collect(Modifier.weight(1f).fillMaxHeight())
                    stock(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
                    prepare(Modifier.fillMaxWidth())
                    collect(Modifier.fillMaxWidth())
                    stock(Modifier.fillMaxWidth())
                }
            }
        }

        if (backup.isDue(now)) {
            val tone = ShowroomTheme.colors.warning
            Surface(shape = MaterialTheme.shapes.large, color = tone.background, contentColor = tone.foreground) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = Dimens.spaceXl, end = Dimens.spaceM, top = Dimens.spaceM, bottom = Dimens.spaceM),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                ) {
                    Icon(Icons.Filled.Schedule, contentDescription = null)
                    Text(
                        "${backup.label(now)}. A backup keeps your buyers, orders and stock safe if this tablet is lost or breaks.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onOpenBackup,
                        colors = ButtonDefaults.buttonColors(containerColor = tone.foreground, contentColor = Color.White),
                    ) { Text("Back up now") }
                }
            }
        }
    }
}

/** One of the three columns: an icon, a title and its figure, then scrolling rows. */
@Composable
private fun TodayCard(
    title: String,
    figure: String,
    icon: ImageVector,
    iconBackground: Color,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Dimens.spaceXl), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Surface(shape = CircleShape, color = iconBackground, modifier = Modifier.size(Dimens.iconBadge)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(Dimens.spaceS))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(figure, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
                content = content,
            )
        }
    }
}

@Composable
private fun TodayRow(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceM), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs), content = content)
    }
}

/** An undelivered order: buyer, stage, what it is, its number and, when set, its delivery date. */
@Composable
private fun OpenOrderRow(order: OpenOrder, today: LocalDate, zone: ZoneId, onClick: () -> Unit) {
    TodayRow(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(order.buyerName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            FulfillmentStatusBadge(order.order.fulfillmentStatus)
        }
        val what = order.firstProduct?.let { if (order.itemCount > 1) "$it + ${order.itemCount - 1} more" else it } ?: "No products"
        Text(
            "$what · ${Formats.orderNumber(order.order)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        order.order.deliveryDate?.let { instant ->
            val day = instant.atZone(zone).toLocalDate()
            val late = day.isBefore(today)
            Text(
                when {
                    late -> "Delivery was due ${Formats.date(instant)}"
                    day == today -> "Deliver today"
                    else -> "Deliver ${Formats.date(instant)}"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val WIDE_FROM = 900.dp
