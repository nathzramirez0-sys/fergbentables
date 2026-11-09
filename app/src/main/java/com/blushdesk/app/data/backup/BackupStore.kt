package com.blushdesk.app.data.backup

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant

/** How often the app reminds the operator to back up. */
enum class BackupReminder(val label: String, val days: Int?) {
    EVERY_3_DAYS("Every 3 days", 3),
    WEEKLY("Weekly", 7),
    OFF("Off", null),
}

/** One backup the app made, for the "Recent backups" list. */
data class BackupRecord(val at: Instant, val fileName: String, val savedTo: String)

data class BackupStatus(
    val lastBackupAt: Instant? = null,
    val reminder: BackupReminder = BackupReminder.WEEKLY,
    /** Newest first. */
    val history: List<BackupRecord> = emptyList(),
) {
    /** Whole days since the last backup, or null if there has never been one. */
    fun daysSince(now: Instant): Long? = lastBackupAt?.let { Duration.between(it, now).toDays().coerceAtLeast(0) }

    /** True when the operator should be nudged: never backed up, or the reminder interval has passed. */
    fun isDue(now: Instant): Boolean {
        val days = reminder.days ?: return false
        val since = daysSince(now) ?: return true
        return since >= days
    }

    /** "Not backed up yet", "Backed up today", "Last backup yesterday", "Last backup 7 days ago". */
    fun label(now: Instant): String = when (val days = daysSince(now)) {
        null -> "Not backed up yet"
        0L -> "Backed up today"
        1L -> "Last backup yesterday"
        else -> "Last backup $days days ago"
    }
}

/**
 * Remembers when the last backup was made, where, and how often to remind. Kept in the app's
 * private preferences, not the database, so restoring an old backup never makes the app think the
 * newest backup is older than it is.
 */
class BackupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(load())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    fun recordBackup(record: BackupRecord) {
        val history = (listOf(record) + _status.value.history).take(MAX_HISTORY)
        prefs.edit {
            putLong(KEY_LAST, record.at.toEpochMilli())
            putString(KEY_HISTORY, history.joinToString("\n") { "${it.at.toEpochMilli()}\t${it.fileName}\t${it.savedTo}" })
        }
        _status.value = _status.value.copy(lastBackupAt = record.at, history = history)
    }

    fun setReminder(reminder: BackupReminder) {
        prefs.edit { putString(KEY_REMINDER, reminder.name) }
        _status.value = _status.value.copy(reminder = reminder)
    }

    private fun load(): BackupStatus = BackupStatus(
        lastBackupAt = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        reminder = prefs.getString(KEY_REMINDER, null)?.let { saved -> BackupReminder.entries.firstOrNull { it.name == saved } }
            ?: BackupReminder.WEEKLY,
        history = prefs.getString(KEY_HISTORY, null).orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            BackupRecord(Instant.ofEpochMilli(at), parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        },
    )

    private companion object {
        const val PREFS = "backup"
        const val KEY_LAST = "lastBackupAt"
        const val KEY_REMINDER = "reminder"
        const val KEY_HISTORY = "history"
        const val MAX_HISTORY = 5
    }
}
