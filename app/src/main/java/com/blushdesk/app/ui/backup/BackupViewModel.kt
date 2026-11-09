package com.blushdesk.app.ui.backup

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blushdesk.app.data.backup.BackupReminder
import com.blushdesk.app.data.backup.BackupService
import com.blushdesk.app.data.backup.BackupStatus
import com.blushdesk.app.data.backup.BackupStore
import com.blushdesk.app.di.AppContainer
import com.blushdesk.app.domain.model.UserFacingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the backup screen is busy with, if anything. */
enum class BackupWork { BACKING_UP, RESTORING }

data class BackupUiState(
    val status: BackupStatus = BackupStatus(),
    val working: BackupWork? = null,
)

sealed interface BackupEvent {
    data class Success(val message: String) : BackupEvent
    data class Error(val message: String) : BackupEvent
    /** The restore finished; the app must restart to open the restored records. */
    data object Restored : BackupEvent
}

/** Runs backups and restores for [BackupScreen]; the backup status itself lives in [BackupStore]. */
class BackupViewModel(
    private val service: BackupService,
    private val store: BackupStore,
) : ViewModel() {

    private val working = MutableStateFlow<BackupWork?>(null)

    val uiState: StateFlow<BackupUiState> = combine(store.status, working) { status, work -> BackupUiState(status, work) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupUiState(store.status.value))

    private val _events = Channel<BackupEvent>(Channel.BUFFERED)
    val events: Flow<BackupEvent> = _events.receiveAsFlow()

    fun backUpToDownloads(password: String) = run(BackupWork.BACKING_UP, "The backup could not be saved.") {
        val file = service.create(password.toCharArray())
        val savedTo = service.saveToDownloads(file)
        _events.send(BackupEvent.Success("Backup saved to $savedTo"))
    }

    fun backUpTo(uri: Uri, password: String) = run(BackupWork.BACKING_UP, "The backup could not be saved there.") {
        val file = service.create(password.toCharArray())
        val name = service.saveTo(uri, file)
        _events.send(BackupEvent.Success("Backup saved as $name"))
    }

    fun restore(uri: Uri, password: String) = run(BackupWork.RESTORING, "The backup could not be restored. Nothing was changed.") {
        service.restore(uri, password.toCharArray())
        _events.send(BackupEvent.Restored)
    }

    fun setReminder(reminder: BackupReminder) = store.setReminder(reminder)

    fun reportError(message: String) {
        _events.trySend(BackupEvent.Error(message))
    }

    private fun run(work: BackupWork, failure: String, block: suspend () -> Unit) {
        if (working.value != null) return
        viewModelScope.launch {
            working.value = work
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserFacingException) {
                _events.send(BackupEvent.Error(e.message ?: failure))
            } catch (e: Exception) {
                Log.e(TAG, failure, e)
                _events.send(BackupEvent.Error(failure))
            } finally {
                working.value = null
            }
        }
    }

    companion object {
        private const val TAG = "BackupViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { BackupViewModel(container.backups, container.backupStore) }
        }
    }
}
