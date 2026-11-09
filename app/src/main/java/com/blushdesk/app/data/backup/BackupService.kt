package com.blushdesk.app.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.blushdesk.app.data.local.database.AppDatabase
import com.blushdesk.app.domain.model.UserFacingException
import com.blushdesk.app.utils.APP_NAME
import com.blushdesk.app.utils.AppFiles
import com.blushdesk.app.utils.DownloadsSaver
import com.blushdesk.app.utils.Formats
import com.blushdesk.app.utils.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant

/**
 * Makes and restores backups: one password-protected file holding the database and every photo.
 *
 * Automatic cloud backup stays off (the records are customers' personal details), so this is the
 * operator's way to keep a copy off the tablet: in Download/FergBentables, on an SD card or USB
 * drive, or anywhere else the system file picker offers.
 */
class BackupService(
    private val context: Context,
    private val database: AppDatabase,
    private val downloads: DownloadsSaver,
    private val store: BackupStore,
) {
    private val databaseFile: File get() = context.getDatabasePath(AppDatabase.FILE_NAME)
    private val photosDir: File get() = File(context.filesDir, PhotoStorage.PHOTOS_DIR)

    /** Writes a backup into the app's cache and returns it; save it with [saveToDownloads] or [saveTo]. */
    suspend fun create(password: CharArray): File = withContext(Dispatchers.IO) {
        val snapshot = File(context.cacheDir, SNAPSHOT_DIR).apply { deleteRecursively(); mkdirs() }
        try {
            // The copy is taken inside a write transaction, so no change can land half-way through it.
            // The write-ahead log goes along with the database file; SQLite replays it on opening.
            val databaseCopies = database.runInTransaction<List<File>> {
                listOf(databaseFile, File(databaseFile.path + WAL))
                    .filter { it.isFile && it.length() > 0 }
                    .map { it.copyTo(File(snapshot, it.name), overwrite = true) }
            }
            val photos = photosDir.listFiles { file -> file.isFile }?.sortedBy { it.name }.orEmpty()
            val now = Instant.now()
            val manifest = BackupManifest(
                createdAt = now,
                appVersion = appVersion(),
                databaseVersion = database.openHelper.readableDatabase.version,
                photoCount = photos.size,
            )
            val backups = AppFiles.cacheDir(context, BACKUPS_DIR)
            val target = File(backups, "$APP_NAME-backup-${Formats.fileStamp(now)}.$EXTENSION")
            BackupCrypto.encrypt(password, target.outputStream().buffered()).use { encrypted ->
                BackupArchive.write(encrypted, manifest, databaseCopies, photos)
            }
            AppFiles.prune(backups, keep = 2)
            target
        } finally {
            snapshot.deleteRecursively()
        }
    }

    /** Copies [backup] to Download/FergBentables and records it; returns where it went. */
    suspend fun saveToDownloads(backup: File): String {
        val savedTo = downloads.save(backup, backup.name, MIME)
        store.recordBackup(BackupRecord(Instant.now(), backup.name, "Download/$APP_NAME"))
        return savedTo
    }

    /** Copies [backup] to a place the operator picked with the system file picker, and records it. */
    suspend fun saveTo(uri: Uri, backup: File): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri, "wt")?.use { out -> backup.inputStream().use { it.copyTo(out) } }
            ?: throw UserFacingException("Could not write to that place. Pick another folder.")
        val name = displayName(uri) ?: backup.name
        store.recordBackup(BackupRecord(Instant.now(), name, "Chosen folder"))
        name
    }

    /**
     * Replaces every record and photo on this tablet with the backup at [uri]. On success the app
     * must restart at once ([restartApp]): the open database still points at the old files.
     */
    suspend fun restore(uri: Uri, password: CharArray): BackupManifest = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, RESTORE_DIR).apply { deleteRecursively(); mkdirs() }
        try {
            val backup = try {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    BackupCrypto.decrypt(password, raw.buffered()).use { plain ->
                        val extracted = BackupArchive.read(KeepOpen(plain), staging, AppDatabase.FILE_NAME)
                        // Read to the end, so the last chunk's check runs and a cut-off file is caught.
                        val sink = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (plain.read(sink) != -1) Unit
                        extracted
                    }
                } ?: throw UserFacingException("Could not open that file.")
            } catch (e: NotABackupException) {
                throw UserFacingException(e.message!!)
            } catch (e: WrongBackupPasswordException) {
                throw UserFacingException(e.message!!)
            } catch (e: DamagedBackupException) {
                throw UserFacingException(e.message!!)
            }
            val current = database.openHelper.readableDatabase.version
            if (backup.manifest.databaseVersion > current) {
                throw UserFacingException("This backup was made with a newer version of $APP_NAME. Update the app, then restore it.")
            }
            swapIn(backup)
            backup.manifest
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Moves the backup's files into place. The current files are parked first and put back if
     * anything fails, so a failed restore leaves the tablet exactly as it was.
     */
    private fun swapIn(backup: ExtractedBackup) {
        val databaseDir = databaseFile.parentFile ?: throw IOException("No database folder")
        val parked = File(context.filesDir, PARKED_DIR).apply { deleteRecursively(); mkdirs() }
        val moved = mutableListOf<Pair<File, File>>()
        val placed = mutableListOf<File>()
        try {
            listOf("", WAL, SHM, JOURNAL).map { File(databaseDir, AppDatabase.FILE_NAME + it) }.filter { it.exists() }.forEach { file ->
                val spot = File(parked, file.name)
                move(file, spot)
                moved += file to spot
            }
            if (photosDir.exists()) {
                val spot = File(parked, PhotoStorage.PHOTOS_DIR)
                move(photosDir, spot)
                moved += photosDir to spot
            }
            backup.databaseFiles.forEach { file ->
                val target = File(databaseDir, file.name)
                move(file, target)
                placed += target
            }
            val photos = photosDir.apply { mkdirs() }
            placed += photos
            backup.photos.forEach { move(it, File(photos, it.name)) }
        } catch (e: Exception) {
            // Only what this restore put in place is removed; then the parked originals go back.
            placed.forEach { it.deleteRecursively() }
            moved.asReversed().forEach { (original, spot) -> move(spot, original) }
            throw e
        }
        parked.deleteRecursively()
    }

    private fun move(from: File, to: File) {
        if (from.renameTo(to)) return
        if (from.isDirectory) {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        } else {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun appVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Lets [BackupArchive.read] close its zip reader without closing the decrypting stream under it. */
    private class KeepOpen(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    companion object {
        const val EXTENSION = "fbk"
        const val MIME = "application/octet-stream"
        private const val WAL = "-wal"
        private const val SHM = "-shm"
        private const val JOURNAL = "-journal"
        private const val BACKUPS_DIR = "backups"
        private const val SNAPSHOT_DIR = "backup-snapshot"
        private const val RESTORE_DIR = "restore"
        private const val PARKED_DIR = "restore-parked"

        /** Starts the app fresh, so it opens the restored database. */
        fun restartApp(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            context.startActivity(Intent.makeRestartActivityTask(launch.component))
            Runtime.getRuntime().exit(0)
        }
    }
}
