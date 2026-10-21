package com.blushdesk.app.ui.buyer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Facebook
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.blushdesk.app.data.local.database.Buyer
import com.blushdesk.app.ui.components.Avatar
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.utils.Formats

/**
 * The selected buyer's profile photo, name, contact details and date added, with Edit, Export
 * (this buyer's own workbook) and Delete.
 */
@Composable
fun BuyerDetailHeader(
    buyer: Buyer,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.spaceXl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
        ) {
            Avatar(
                imageUri = buyer.profileImageUri,
                name = buyer.fullName,
                size = Dimens.avatarLarge,
                ring = BorderStroke(Dimens.avatarRing, MaterialTheme.colorScheme.primaryContainer),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                Text(
                    text = buyer.fullName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (buyer.contactNumber.isNotBlank()) InfoLine(Icons.Filled.Phone, buyer.contactNumber, "Contact number")
                if (buyer.facebookName.isNotBlank()) InfoLine(Icons.Filled.Facebook, buyer.facebookName, "Facebook name")
                if (buyer.email.isNotBlank()) InfoLine(Icons.Filled.Email, buyer.email, "Email")
                InfoLine(Icons.Filled.CalendarMonth, "Added ${Formats.date(buyer.dateAdded)}", "Date added")
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Edit")
                }
                OutlinedButton(onClick = onExport) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Export")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium), tint = MaterialTheme.colorScheme.error)
                    Text("  Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconMedium))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
