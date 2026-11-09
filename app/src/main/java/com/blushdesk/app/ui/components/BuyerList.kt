package com.blushdesk.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.blushdesk.app.data.local.database.BuyerListItem
import com.blushdesk.app.ui.theme.Dimens

/**
 * Adds the buyer list to a LazyColumn: a header with the buyer count (and [headerAction], the sort
 * switch), one [BuyerListItemCard] per buyer, or an empty state: no buyers yet, nothing matches the
 * search, or nobody fits the filter ([filterMessage]). Written as a LazyListScope extension so the
 * master pane can put its own items above it in the same scrolling list.
 */
fun LazyListScope.buyerList(
    buyers: List<BuyerListItem>,
    loaded: Boolean,
    searching: Boolean,
    selectedBuyerId: Long?,
    onSelect: (Long) -> Unit,
    filterMessage: String? = null,
    headerAction: @Composable () -> Unit = {},
) {
    when {
        !loaded -> item(key = "buyers-loading") { LoadingIndicator("Loading buyers…") }

        buyers.isEmpty() && filterMessage != null -> item(key = "buyers-filtered") {
            EmptyState(icon = Icons.Filled.FilterAlt, title = "Nobody here", message = filterMessage)
        }

        buyers.isEmpty() -> item(key = "buyers-empty") {
            EmptyState(
                icon = if (searching) Icons.Filled.PersonSearch else Icons.Filled.People,
                title = if (searching) "No matches" else "No buyers yet",
                message = if (searching) {
                    "No buyer has that name, phone number or email."
                } else {
                    "Tap Add buyer to record your first customer."
                },
            )
        }

        else -> {
            item(key = "buyers-count") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "BUYERS (${buyers.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = Dimens.spaceXs)
                            .semantics { heading() },
                    )
                    headerAction()
                }
            }
            items(buyers, key = { it.buyer.id }) { item ->
                BuyerListItemCard(
                    item = item,
                    selected = item.buyer.id == selectedBuyerId,
                    onClick = { onSelect(item.buyer.id) },
                )
            }
        }
    }
}
