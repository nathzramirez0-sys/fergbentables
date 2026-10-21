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
import com.blushdesk.app.ui.components.PhotoActions
import com.blushdesk.app.ui.inventory.InventoryScreen
import com.blushdesk.app.ui.inventory.InventoryTab
import com.blushdesk.app.ui.inventory.InventoryViewModel
import com.blushdesk.app.ui.showroom.ShowroomTabletScreen
import com.blushdesk.app.ui.showroom.ShowroomViewModel
import kotlinx.coroutines.flow.StateFlow

/** The app's two screens. */
enum class AppScreen { SHOWROOM, INVENTORY }

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
    openRequests: StateFlow<String?>,
    onOpenRequestHandled: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.SHOWROOM) }
    val states = rememberSaveableStateHolder()
    val inventoryState by inventory.uiState.collectAsStateWithLifecycle()
    val showroomState by showroom.uiState.collectAsStateWithLifecycle()
    val openRequest by openRequests.collectAsStateWithLifecycle()
    val photoActions = remember(showroom) { PhotoActions(importPhoto = showroom::importPhoto, discard = showroom::discardPhoto) }

    LaunchedEffect(openRequest) {
        if (openRequest == OPEN_INVENTORY_NOTIFICATIONS) {
            inventory.selectTab(InventoryTab.NOTIFICATIONS)
            screen = AppScreen.INVENTORY
            onOpenRequestHandled()
        }
    }

    states.SaveableStateProvider(screen.name) {
        when (screen) {
            AppScreen.SHOWROOM -> ShowroomTabletScreen(
                viewModel = showroom,
                inventory = inventoryState.summary,
                unreadNotifications = inventoryState.unread,
                onOpenInventory = { screen = AppScreen.INVENTORY },
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
        }
    }
}

const val OPEN_INVENTORY_NOTIFICATIONS = "inventory-notifications"
