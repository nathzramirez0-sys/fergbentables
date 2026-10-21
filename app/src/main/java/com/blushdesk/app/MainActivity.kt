package com.blushdesk.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.blushdesk.app.ui.AppRoot
import com.blushdesk.app.ui.inventory.InventoryViewModel
import com.blushdesk.app.ui.showroom.ShowroomViewModel
import com.blushdesk.app.ui.theme.ShowroomPinkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as FergBentablesApp).container

    private val showroom: ShowroomViewModel by viewModels { ShowroomViewModel.factory(container) }
    private val inventory: InventoryViewModel by viewModels { InventoryViewModel.factory(container) }

    /** What a notification tap asked to open; cleared once the screen has handled it. */
    private val openRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme is light-only, so the system bars always use dark icons on a light scrim.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Only a fresh launch reads the intent; after recreation the request was handled already.
        if (savedInstanceState == null) handle(intent)
        setContent {
            ShowroomPinkTheme {
                AppRoot(
                    showroom = showroom,
                    inventory = inventory,
                    openRequests = openRequests,
                    onOpenRequestHandled = { openRequests.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Catch up on reminders that came due while the background job had not run yet.
        lifecycleScope.launch {
            runCatching { container.notifier.sync() }
        }
    }

    private fun handle(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_INVENTORY_NOTIFICATIONS) openRequests.value = OPEN_INVENTORY_NOTIFICATIONS
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_INVENTORY_NOTIFICATIONS = com.blushdesk.app.ui.OPEN_INVENTORY_NOTIFICATIONS
    }
}
