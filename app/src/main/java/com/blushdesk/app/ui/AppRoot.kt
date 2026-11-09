package com.blushdesk.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blushdesk.app.data.security.PinLock
import com.blushdesk.app.data.security.PinState
import com.blushdesk.app.ui.backup.BackupScreen
import com.blushdesk.app.ui.backup.BackupViewModel
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.inventory.InventoryScreen
import com.blushdesk.app.ui.inventory.InventoryTab
import com.blushdesk.app.ui.inventory.InventoryViewModel
import com.blushdesk.app.ui.security.LockScreen
import com.blushdesk.app.ui.security.PinSettingsDialog
import com.blushdesk.app.ui.showroom.ShowroomTabletScreen
import com.blushdesk.app.ui.showroom.ShowroomViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The app's screens. */
enum class AppScreen { SHOWROOM, INVENTORY, BACKUP }

/**
 * Switches between the showroom and the Inventory System. Each screen keeps its own saved state
 * (open dialog, typed text) while the other is showing, through a SaveableStateHolder, so going
 * to the inventory and back returns to exactly where the operator was. Back from the inventory
 * returns to the showroom.
 *
 * [openRequests] carries a request from outside the screen, for now a tap on an inventory
 * notification, which opens the inventory's Notifications tab.
 */
@Composable
fun AppRoot(
    showroom: ShowroomViewModel,
    inventory: InventoryViewModel,
    backup: BackupViewModel,
    openRequests: StateFlow<String?>,
    onOpenRequestHandled: () -> Unit,
    /** The optional PIN lock; while it is locked only the lock screen shows. */
    pinLock: PinLock? = null,
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.SHOWROOM) }
    val states = rememberSaveableStateHolder()
    val inventoryState by inventory.uiState.collectAsStateWithLifecycle()
    val showroomState by showroom.uiState.collectAsStateWithLifecycle()
    val backupState by backup.uiState.collectAsStateWithLifecycle()
    val openRequest by openRequests.collectAsStateWithLifecycle()
    val photoActions = remember(showroom) { PhotoActions(importPhoto = showroom::importPhoto, discard = showroom::discardPhoto) }

    LaunchedEffect(openRequest) {
        if (openRequest == OPEN_INVENTORY_NOTIFICATIONS) {
            inventory.selectTab(InventoryTab.NOTIFICATIONS)
            screen = AppScreen.INVENTORY
            onOpenRequestHandled()
        }
    }

    val lock by (pinLock?.state ?: remember { MutableStateFlow(PinState()) }).collectAsStateWithLifecycle()
    var pinSettings by rememberSaveable { mutableStateOf(false) }
    if (lock.locked && pinLock != null) {
        // The screens leave composition while locked; the state holder keeps what they had saved.
        LockScreen(state = lock, onUnlock = { pinLock.unlock(it) })
        return
    }

    if (pinSettings && pinLock != null) {
        PinSettingsDialog(
            state = lock,
            onCheck = pinLock::check,
            onSetPin = pinLock::setPin,
            onLockAfter = pinLock::setLockAfter,
            onTurnOff = pinLock::turnOff,
            onDismiss = { pinSettings = false },
        )
    }

    states.SaveableStateProvider(screen.name) {
        when (screen) {
            AppScreen.SHOWROOM -> ShowroomTabletScreen(
                viewModel = showroom,
                inventory = inventoryState.summary,
                unreadNotifications = inventoryState.unread,
                onOpenInventory = { screen = AppScreen.INVENTORY },
                backup = backupState.status,
                onOpenBackup = { screen = AppScreen.BACKUP },
                onOpenPinLock = if (pinLock != null) ({ pinSettings = true }) else null,
            )
            AppScreen.INVENTORY -> {
                BackHandler { screen = AppScreen.SHOWROOM }
                InventoryScreen(
                    viewModel = inventory,
                    storeName = showroomState.operator.storeName,
                    photoActions = photoActions,
                    onBack = { screen = AppScreen.SHOWROOM },
                )
            }
            AppScreen.BACKUP -> {
                BackHandler { screen = AppScreen.SHOWROOM }
                BackupScreen(viewModel = backup, onBack = { screen = AppScreen.SHOWROOM })
            }
        }
    }
}

const val OPEN_INVENTORY_NOTIFICATIONS = "inventory-notifications"
