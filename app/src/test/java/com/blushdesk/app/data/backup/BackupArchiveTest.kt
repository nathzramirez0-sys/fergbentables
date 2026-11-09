package com.blushdesk.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val manifest = BackupManifest(
        createdAt = Instant.parse("2026-10-20T14:31:00Z"),
        appVersion = "1.3.0",
        databaseVersion = 6,
        photoCount = 2,
    )

    private fun file(name: String, text: String) = File(temp.newFolder(), name).apply { writeText(text) }

    @Test
    fun `writes and reads back the manifest, database and photos`() {
        val db = file("showroom.db", "records")
        val wal = file("showroom.db-wal", "recent changes")
        val photos = listOf(file("a.jpg", "photo a"), file("b.jpg", "photo b"))
        val zipped = ByteArrayOutputStream().also { BackupArchive.write(it, manifest, listOf(db, wal), photos) }.toByteArray()

        val into = temp.newFolder("restore")
        val read = BackupArchive.read(ByteArrayInputStream(zipped), into, "showroom.db")

        assertEquals(manifest, read.manifest)
        assertEquals(setOf("showroom.db", "showroom.db-wal"), read.databaseFiles.map { it.name }.toSet())
        assertEquals("records", read.databaseFiles.first { it.name == "showroom.db" }.readText())
        assertEquals(listOf("photo a", "photo b"), read.photos.sortedBy { it.name }.map { it.readText() })
        assertTrue(read.photos.all { it.parentFile == File(into, "photos") })
    }

    @Test
    fun `entries that try to leave the folder are skipped`() {
        val zipped = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.properties"))
                zip.write("format=1\ncreatedAt=2026-10-20T14:31:00Z\nappVersion=1.3.0\ndatabaseVersion=6\nphotoCount=0\n".toByteArray())
                zip.putNextEntry(ZipEntry("database/showroom.db"))
                zip.write("records".toByteArray())
                zip.putNextEntry(ZipEntry("photos/../../evil.txt"))
                zip.write("evil".toByteArray())
                zip.putNextEntry(ZipEntry("database/other.db"))
                zip.write("not ours".toByteArray())
            }
        }.toByteArray()

        val into = temp.newFolder("restore")
        val read = BackupArchive.read(ByteArrayInputStream(zipped), into, "showroom.db")

        assertEquals(listOf("showroom.db"), read.databaseFiles.map { it.name })
        assertTrue(read.photos.isEmpty())
        assertFalse(File(into.parentFile, "evil.txt").exists())
        assertNull(into.walk().firstOrNull { it.name == "evil.txt" || it.name == "other.db" })
    }

    @Test
    fun `an archive without the database is damaged`() {
        val zipped = ByteArrayOutputStream().also { BackupArchive.write(it, manifest, emptyList(), emptyList()) }.toByteArray()
        assertThrows(DamagedBackupException::class.java) {
            BackupArchive.read(ByteArrayInputStream(zipped), temp.newFolder("restore"), "showroom.db")
        }
    }

    @Test
    fun `backup status says when a backup is due`() {
        val now = LocalDate.of(2026, 10, 20).atTime(21, 0).toInstant(ZoneOffset.UTC)
        val never = BackupStatus()
        assertTrue(never.isDue(now))
        assertEquals("Not backed up yet", never.label(now))

        val weekOld = BackupStatus(lastBackupAt = now.minusSeconds(7 * DAY), reminder = BackupReminder.WEEKLY)
        assertTrue(weekOld.isDue(now))
        assertEquals("Last backup 7 days ago", weekOld.label(now))

        val sixDays = weekOld.copy(lastBackupAt = now.minusSeconds(6 * DAY + 3_600))
        assertFalse(sixDays.isDue(now))
        assertTrue(sixDays.copy(reminder = BackupReminder.EVERY_3_DAYS).isDue(now))
        assertFalse(weekOld.copy(reminder = BackupReminder.OFF).isDue(now))

        assertEquals("Backed up today", BackupStatus(lastBackupAt = now.minusSeconds(60)).label(now))
        assertEquals("Last backup yesterday", BackupStatus(lastBackupAt = now.minusSeconds(DAY + 60)).label(now))
    }

    @Test
    fun `backup password needs six characters and a matching copy`() {
        assertEquals("Use at least 6 characters.", BackupPassword.problem("rose", "rose"))
        assertEquals("The password can't be only spaces.", BackupPassword.problem("       ", "       "))
        assertEquals("The two passwords don't match.", BackupPassword.problem("roseshowroom", "roseshowrom"))
        assertNull(BackupPassword.problem("roseshowroom", "roseshowroom"))
    }

    private companion object {
        const val DAY = 86_400L
    }
}
