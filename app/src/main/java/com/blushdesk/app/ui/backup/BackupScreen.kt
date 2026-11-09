package com.blushdesk.app.ui.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blushdesk.app.data.backup.BackupPassword
import com.blushdesk.app.data.backup.BackupReminder
import com.blushdesk.app.data.backup.BackupService
import com.blushdesk.app.data.backup.BackupStatus
import com.blushdesk.app.ui.components.SegmentedChoice
import com.blushdesk.app.ui.components.ShowroomSnackbarHost
import com.blushdesk.app.ui.components.show
import com.blushdesk.app.ui.theme.Dimens
import com.blushdesk.app.ui.theme.ShowroomTheme
import com.blushdesk.app.utils.APP_NAME
import com.blushdesk.app.utils.Formats
import kotlinx.coroutines.launch
import java.time.Instant

private enum class BackupDestination { DOWNLOADS, OTHER_PLACE }

/**
 * Backup and restore: the last backup and a Back up now button, where to save it, the password
 * that locks it, how often to be reminded, the recent backups, and restoring one.
 *
 * The password is held in memory only (never saved with the screen), so it is gone once the
 * operator leaves this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(BackupDestination.DOWNLOADS) }
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf(false) }
    val passwordProblem = BackupPassword.problem(password, confirmation)

    val pickPlace = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupService.MIME)) { uri ->
        when {
            uri == null -> Unit
            // Android may have closed the app while the picker was open, and the password with it.
            password.isEmpty() -> viewModel.reportError("Type the backup password again, then tap Back up now.")
            else -> viewModel.backUpTo(uri, password)
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreError = null
            restoreUri = uri
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupEvent.Success -> {
                    password = ""
                    confirmation = ""
                    attempted = false
                    scope.launch { snackbar.show(event.message, isError = false) }
                }
                is BackupEvent.Error ->
                    if (restoreUri != null) restoreError = event.message else scope.launch { snackbar.show(event.message, isError = true) }
                BackupEvent.Restored -> {
                    restoreUri = null
                    restored = true
                }
            }
        }
    }

    fun backUp() {
        attempted = true
        if (passwordProblem != null) return
        when (destination) {
            BackupDestination.DOWNLOADS -> viewModel.backUpToDownloads(password)
            BackupDestination.OTHER_PLACE -> pickPlace.launch("$APP_NAME-backup-${Formats.fileStamp(Instant.now())}.${BackupService.EXTENSION}")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { ShowroomSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text("Backup and restore", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
                        Text(
                            "Keep a copy of every buyer, order and product off this tablet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= WIDE_FROM
            val now = remember(state.status) { Instant.now() }
            val main: @Composable ColumnScope.() -> Unit = {
                StatusCard(status = state.status, now = now, backingUp = state.working == BackupWork.BACKING_UP, enabled = state.working == null, onBackUp = ::backUp)
                DestinationCard(destination = destination, onChange = { destination = it })
                PasswordCard(
                    password = password,
                    confirmation = confirmation,
                    onPasswordChange = { password = it },
                    onConfirmationChange = { confirmation = it },
                    error = passwordProblem.takeIf { attempted },
                )
                ReminderCard(reminder = state.status.reminder, onChange = viewModel::setReminder)
            }
            val side: @Composable ColumnScope.() -> Unit = {
                HistoryCard(status = state.status)
                RestoreCard(enabled = state.working == null, onChoose = { pickBackup.launch(arrayOf("*/*")) })
            }
            val scroll = rememberScrollState()
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Dimens.spaceXxxl, vertical = Dimens.spaceXxl),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXxl),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceL), content = main)
                    Column(Modifier.width(SIDE_WIDTH), verticalArrangement = Arrangement.spacedBy(Dimens.spaceL), content = side)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(Dimens.spaceL),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
                ) {
                    main()
                    side()
                }
            }
        }
    }

    restoreUri?.let { uri ->
        RestoreDialog(
            fileName = remember(uri) { displayName(context, uri) },
            restoring = state.working == BackupWork.RESTORING,
            error = restoreError,
            onRestore = { typed ->
                restoreError = null
                viewModel.restore(uri, typed)
            },
            onDismiss = {
                restoreUri = null
                restoreError = null
            },
        )
    }

    if (restored) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ShowroomTheme.colors.success.foreground) },
            title = { Text("Backup restored") },
            text = { Text("$APP_NAME will restart to open the restored records.") },
            confirmButton = { Button(onClick = { BackupService.restartApp(context) }) { Text("Restart now") } },
        )
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.border, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Dimens.spaceXl), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM), content = content)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun StatusCard(status: BackupStatus, now: Instant, backingUp: Boolean, enabled: Boolean, onBackUp: () -> Unit) {
    val due = status.isDue(now) || status.lastBackupAt == null
    val tone = if (due) ShowroomTheme.colors.warning else ShowroomTheme.colors.success
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = tone.background,
        contentColor = tone.foreground,
    ) {
        Row(
            modifier = Modifier.padding(Dimens.spaceXl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL),
        ) {
            Icon(if (due) Icons.Filled.Schedule else Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                Text(
                    status.lastBackupAt?.let { "Last backup: ${Formats.dateTime(it)}" } ?: "No backup yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when {
                        status.lastBackupAt == null -> "Make one now, so a lost or broken tablet doesn't take your records with it."
                        due -> "${status.label(now)}. Anything added since then is only on this tablet."
                        else -> "${status.label(now)}. Back up again after a busy day."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onBackUp, enabled = enabled) {
                if (backingUp) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.iconMedium),
                        strokeWidth = Dimens.progressStroke,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text("  Backing up…")
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
                    Text("  Back up now")
                }
            }
        }
    }
}

@Composable
private fun DestinationCard(destination: BackupDestination, onChange: (BackupDestination) -> Unit) {
    SectionCard {
        CardTitle("Where to save it")
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            DestinationOption(
                selected = destination == BackupDestination.DOWNLOADS,
                title = "Download/$APP_NAME on this tablet",
                hint = "Copy the file to a computer or a USB drive afterwards, so a lost tablet doesn't take the backup with it.",
                onClick = { onChange(BackupDestination.DOWNLOADS) },
            )
            DestinationOption(
                selected = destination == BackupDestination.OTHER_PLACE,
                title = "An SD card, USB drive or another place",
                hint = "You pick the place after tapping Back up now.",
                onClick = { onChange(BackupDestination.OTHER_PLACE) },
            )
        }
    }
}

@Composable
private fun DestinationOption(selected: Boolean, title: String, hint: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) Dimens.borderSelected else Dimens.border,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(Dimens.spaceM), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(top = Dimens.spaceXs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PasswordCard(
    password: String,
    confirmation: String,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    error: String?,
) {
    var visible by remember { mutableStateOf(false) }
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    SectionCard {
        CardTitle("Backup password")
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                isError = error != null,
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = confirmation,
                onValueChange = onConfirmationChange,
                label = { Text("Type it again") },
                singleLine = true,
                isError = error != null,
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.weight(1f),
            )
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "The file is locked with this password. Without it nobody can open the backup, not even you, so write it down somewhere safe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReminderCard(reminder: BackupReminder, onChange: (BackupReminder) -> Unit) {
    SectionCard {
        CardTitle("Remind me to back up")
        SegmentedChoice(BackupReminder.entries, reminder, { it.label }, onChange)
    }
}

@Composable
private fun HistoryCard(status: BackupStatus) {
    SectionCard {
        CardTitle("Recent backups")
        if (status.history.isEmpty()) {
            Text(
                "No backups yet. Each one you make is listed here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status.history.forEachIndexed { index, record ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(record.fileName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${Formats.dateTime(record.at)} · ${record.savedTo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreCard(enabled: Boolean, onChoose: () -> Unit) {
    SectionCard {
        CardTitle("Restore from a backup")
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
            Row(Modifier.padding(Dimens.spaceM), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(Dimens.iconLarge))
                Text(
                    "Restoring replaces everything on this tablet (buyers, orders, products and photos) with what is in the backup.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        OutlinedButton(
            onClick = onChoose,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(Dimens.iconMedium))
            Text("  Choose a backup file")
        }
    }
}

@Composable
private fun RestoreDialog(
    fileName: String?,
    restoring: Boolean,
    error: String?,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        icon = { Icon(Icons.Filled.Restore, contentDescription = null) },
        title = { Text("Restore this backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                if (fileName != null) Text(fileName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Everything on this tablet will be replaced with the backup's buyers, orders, products and photos. " +
                        "Make a backup of what's here first if you may need it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup password") },
                    singleLine = true,
                    enabled = !restoring,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRestore(password) },
                enabled = password.isNotEmpty() && !restoring,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) {
                Text(if (restoring) "Restoring…" else "Restore")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !restoring) { Text("Cancel") } },
    )
}

private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

private val WIDE_FROM = 900.dp
private val SIDE_WIDTH = 420.dp
