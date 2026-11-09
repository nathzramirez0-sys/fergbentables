package com.blushdesk.app.ui.order

import androidx.compose.runtime.Composable
import com.blushdesk.app.data.local.database.Order
import com.blushdesk.app.data.local.database.OrderItem
import com.blushdesk.app.data.local.database.OrderWithItems
import com.blushdesk.app.data.local.database.Product

/** Edits an existing order and its products; every total is recalculated from the new lines. */
@Composable
fun EditOrderDialog(
    order: OrderWithItems,
    buyerName: String,
    products: List<Product>,
    onSave: (Order, List<OrderItem>) -> Unit,
    onDismiss: () -> Unit,
) = OrderFormDialog(
    buyerId = order.order.buyerId,
    buyerName = buyerName,
    initial = order,
    products = products,
    onSave = { edited, items, _ -> onSave(edited, items) },
    onDismiss = onDismiss,
)
