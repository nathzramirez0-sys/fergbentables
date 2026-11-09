package com.blushdesk.app.ui.order

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.data.local.database.Payment
import com.blushdesk.app.domain.model.FulfillmentStatus
import com.blushdesk.app.domain.model.PaymentStatus
import com.blushdesk.app.ui.components.FulfillmentProgress
import com.blushdesk.app.ui.components.FulfillmentStatusBadge
import com.blushdesk.app.ui.components.InlineMessage
import com.blushdesk.app.ui.components.OrderHeadline
import com.blushdesk.app.ui.components.PaymentStatusBadge
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.Money

/** What an expanded order can ask for. */
class OrderActions(
    val onEdit: (OrderWithItems) -> Unit,
    val onDelete: (OrderWithItems) -> Unit,
    val onAdvance: (OrderWithItems) -> Unit,
    val onSetFulfillment: (OrderWithItems, FulfillmentStatus) -> Unit,
    val onMarkPaid: (OrderWithItems) -> Unit,
    val onReceipt: (OrderWithItems) -> Unit,
    val onRecordPayment: (OrderWithItems) -> Unit = {},
    val onDeletePayment: (Payment) -> Unit = {},
    val onOrderSlip: (OrderWithItems) -> Unit = {},
    val onEditDelivery: (OrderWithItems) -> Unit = {},
)

/**
 * The expanded order. Its actions come first (record a payment, mark it paid, move it a stage,
 * the PDF), then the payments with the balance, the product lines, the purchase facts, the
 * fulfillment progress and the delivery details.
 *
 * Until the balance is ₱0.00 the PDF is an order slip showing what is still due; once paid it is
 * the paid receipt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderDetailCard(
    order: OrderWithItems,
    receiptBusy: Boolean,
    anyReceiptBusy: Boolean,
    actions: OrderActions,
    modifier: Modifier = Modifier,
) {
    val details = order.order
    val paid = details.paymentStatus.isPaid

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.borderSelected, MaterialTheme.colorScheme.primary),
    ) {
        Column(modifier = Modifier.padding(Dimens.spaceXl), verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
            OrderHeadline(order)

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (!paid) {
                    Button(onClick = { actions.onRecordPayment(order) }) {
                        Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                        Text("  Record payment")
                    }
                    OutlinedButton(onClick = { actions.onMarkPaid(order) }) { Text("Mark as paid") }
                }
                details.fulfillmentStatus.next?.let { next ->
                    FilledTonalButton(onClick = { actions.onAdvance(order) }) {
                        Text("Move to ${next.label}")
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.padding(start = Dimens.spaceS).size(Dimens.iconMedium),
                        )
                    }
                }
                if (paid) {
                    Button(onClick = { actions.onReceipt(order) }, enabled = !anyReceiptBusy) {
                        DocumentIcon(busy = receiptBusy, icon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium)) })
                        Text(if (receiptBusy) "  Creating…" else "  Paid receipt")
                    }
                } else {
                    OutlinedButton(onClick = { actions.onOrderSlip(order) }, enabled = !anyReceiptBusy) {
                        DocumentIcon(busy = receiptBusy, icon = { Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium)) })
                        Text(if (receiptBusy) "  Creating…" else "  Order slip")
                    }
                }
                val label = Formats.itemsSummary(order.items.map { it.productName })
                IconButton(onClick = { actions.onEdit(order) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit order $label", tint = MaterialTheme.colorScheme.secondary)
                }
                IconButton(onClick = { actions.onDelete(order) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete order $label", tint = MaterialTheme.colorScheme.error)
                }
            }

            PaymentsPanel(order, onDeletePayment = actions.onDeletePayment)

            ItemsTable(order)

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxl),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
            ) {
                Fact("Purchase date") { FactText(Formats.date(details.purchaseDateTime)) }
                Fact("Purchase time") { FactText(Formats.time(details.purchaseDateTime)) }
                Fact("Payment method") { FactText(details.paymentMode.label) }
                Fact("Payment status") { PaymentStatusBadge(details.paymentStatus) }
                Fact("Fulfillment status") { FulfillmentStatusBadge(details.fulfillmentStatus) }
            }

            Caption("ORDER LIFECYCLE")
            FulfillmentProgress(current = details.fulfillmentStatus, onSelect = { actions.onSetFulfillment(order, it) })

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Delivery(order, onEdit = { actions.onEditDelivery(order) })
        }
    }
}

@Composable
private fun DocumentIcon(busy: Boolean, icon: @Composable () -> Unit) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.iconMedium),
            strokeWidth = Dimens.progressStroke,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        icon()
    }
}

/**
 * The money side of the order: a bar of how much of the total is paid, every payment received
 * (each removable, with Undo in case of a mis-tap) and the balance still due.
 */
@Composable
private fun PaymentsPanel(order: OrderWithItems, onDeletePayment: (Payment) -> Unit) {
    val total = order.order.totalAmount
    val paidAmount = order.paidAmount
    val done = order.order.paymentStatus == PaymentStatus.PAID
    val accent = if (done) ShowroomTheme.colors.success.foreground else MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Payments",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text("Balance  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(order.balance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            val ratio = if (total.signum() > 0) (paidAmount.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 1f
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = accent,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row {
                Text(
                    "Paid ${Money.format(paidAmount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("of ${Money.format(total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (order.payments.isEmpty()) {
                Text("No payments yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            order.payments.forEach { payment ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.padding(start = Dimens.spaceM),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                    ) {
                        Text(Formats.dateTime(payment.paidAt), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 170.dp))
                        Text(payment.paymentMode.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 110.dp))
                        Text(
                            payment.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(Money.format(payment.amount), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { onDeletePayment(payment) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove the payment of ${Money.format(payment.amount)}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!done) {
                InlineMessage(
                    "The paid receipt unlocks when the balance reaches ₱0.00. Until then, the order slip shows what is still due.",
                    isError = false,
                )
            }
        }
    }
}

/** Where and when the order is delivered, or a button to add it. */
@Composable
private fun Delivery(order: OrderWithItems, onEdit: () -> Unit) {
    val details = order.order
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Caption("DELIVERY")
        if (details.deliveryAddress.isBlank() && details.deliveryDate == null) {
            OutlinedButton(onClick = onEdit) {
                Icon(Icons.Filled.LocalShipping, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                Text("  Add delivery address and date")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconMedium))
                Text(
                    details.deliveryAddress.ifBlank { "No address yet" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconMedium))
                Text(details.deliveryDate?.let { Formats.date(it) } ?: "No date yet", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onEdit) { Text("Edit") }
            }
        }
    }
}

/** Every product line: name, quantity × unit price, and the line total; then the order total. */
@Composable
private fun ItemsTable(order: OrderWithItems) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Caption("ITEMS (${order.items.size})")
        order.items.forEach { item ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Text(
                    text = item.productName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${item.quantity} × ${Money.format(item.unitPrice)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 140.dp),
                )
                Text(
                    text = Money.format(item.lineTotal),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(130.dp),
                )
            }
        }
        if (order.items.size > 1) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Order total",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Money.format(order.order.totalAmount),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
}

/** A small caption above a value. */
@Composable
private fun Fact(label: String, value: @Composable () -> Unit) {
    Column(modifier = Modifier.widthIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

@Composable
private fun FactText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}
