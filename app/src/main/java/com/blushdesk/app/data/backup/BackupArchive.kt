package com.blushdesk.app.data.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a backup says about itself. */
data class BackupManifest(
    val createdAt: Instant,
    val appVersion: String,
    /** The Room schema version the backup's database was written with. */
    val databaseVersion: Int,
    val photoCount: Int,
)

/** A backup unpacked into a folder: its manifest, its database files and its photos. */
data class ExtractedBackup(
    val manifest: BackupManifest,
    val databaseFiles: List<File>,
    val photos: List<File>,
)

/**
 * The inside of a backup, before encryption: a zip with `manifest.properties`, the database file
 * (and its write-ahead log, when there is one) under `database/`, and every photo under `photos/`.
 *
 * Reading accepts only those names, and only plain file names inside them, so a crafted file can
 * never write outside the folder it is unpacked into.
 */
object BackupArchive {
    private const val MANIFEST = "manifest.properties"
    private const val DATABASE_DIR = "database/"
    private const val PHOTOS_DIR = "photos/"
    private const val FORMAT = "1"
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

    fun write(out: OutputStream, manifest: BackupManifest, databaseFiles: List<File>, photos: List<File>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            Properties().apply {
                setProperty("format", FORMAT)
                setProperty("createdAt", manifest.createdAt.toString())
                setProperty("appVersion", manifest.appVersion)
                setProperty("databaseVersion", manifest.databaseVersion.toString())
                setProperty("photoCount", manifest.photoCount.toString())
            }.store(zip, "FergBentables backup")
            zip.closeEntry()
            databaseFiles.forEach { zip.add(DATABASE_DIR + it.name, it) }
            photos.forEach { zip.add(PHOTOS_DIR + it.name, it) }
        }
    }

    /**
     * Unpacks [input] into [into] (which should be empty) and checks that it holds a manifest and
     * the database named [databaseName]. Throws [DamagedBackupException] otherwise.
     */
    fun read(input: InputStream, into: File, databaseName: String): ExtractedBackup {
        val databaseDir = File(into, "database").apply { mkdirs() }
        val photosDir = File(into, "photos").apply { mkdirs() }
        var manifest: BackupManifest? = null
        val databaseFiles = mutableListOf<File>()
        val photos = mutableListOf<File>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == MANIFEST -> manifest = parseManifest(zip)
                    name.startsWith(DATABASE_DIR) -> {
                        val file = name.removePrefix(DATABASE_DIR)
                        if (file == databaseName || file == "$databaseName-wal") {
                            databaseFiles += File(databaseDir, file).also { target -> target.outputStream().use { zip.copyTo(it) } }
                        }
                    }
                    name.startsWith(PHOTOS_DIR) -> {
                        val file = name.removePrefix(PHOTOS_DIR)
                        if (SAFE_NAME.matches(file) && file != "." && file != "..") {
                            photos += File(photosDir, file).also { target -> target.outputStream().use { zip.copyTo(it) } }
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        val found = manifest ?: throw DamagedBackupException()
        if (databaseFiles.none { it.name == databaseName }) throw DamagedBackupException("This backup has no records in it.")
        return ExtractedBackup(found, databaseFiles, photos)
    }

    private fun parseManifest(input: InputStream): BackupManifest {
        // Properties.load reads to the end of what it is given; the entry boundary stops it.
        val props = Properties().apply { load(input.reader(Charsets.ISO_8859_1)) }
        if (props.getProperty("format") != FORMAT) throw DamagedBackupException("This backup was made in a format this version can't read.")
        return try {
            BackupManifest(
                createdAt = Instant.parse(props.getProperty("createdAt")),
                appVersion = props.getProperty("appVersion").orEmpty(),
                databaseVersion = props.getProperty("databaseVersion").toInt(),
                photoCount = props.getProperty("photoCount")?.toIntOrNull() ?: 0,
            )
        } catch (e: DateTimeParseException) {
            throw DamagedBackupException()
        } catch (e: NumberFormatException) {
            throw DamagedBackupException()
        } catch (e: NullPointerException) {
            throw DamagedBackupException()
        }
    }

    private fun ZipOutputStream.add(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
