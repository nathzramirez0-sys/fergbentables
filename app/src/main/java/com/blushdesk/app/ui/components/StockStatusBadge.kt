package com.blushdesk.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.blushdesk.app.domain.model.StockStatus
import com.blushdesk.app.ui.theme.ShowroomTheme

/** In stock (green), Low stock (amber), Out of stock (red), or gray for products withdrawn from sale. */
@Composable
fun StockStatusBadge(status: StockStatus, modifier: Modifier = Modifier) {
    StatusBadge(
        label = status.label,
        tone = ShowroomTheme.colors.tone(status),
        icon = when (status) {
            StockStatus.IN_STOCK -> Icons.Filled.CheckCircle
            StockStatus.LOW_STOCK -> Icons.Filled.Warning
            StockStatus.OUT_OF_STOCK -> Icons.Filled.RemoveShoppingCart
            StockStatus.UNAVAILABLE -> Icons.Filled.DoNotDisturbOn
            StockStatus.DISCONTINUED -> Icons.Filled.Block
            StockStatus.ARCHIVED -> Icons.Filled.Archive
        },
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "Stock ${status.label}" },
    )
}
