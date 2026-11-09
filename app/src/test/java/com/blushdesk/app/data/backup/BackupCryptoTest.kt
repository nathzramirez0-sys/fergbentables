package com.blushdesk.app.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class BackupCryptoTest {

    private val password = "rose-showroom".toCharArray()

    private fun encrypt(data: ByteArray, pw: CharArray = password): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(pw, out, rounds = FAST_ROUNDS).use { it.write(data) }
        return out.toByteArray()
    }

    private fun decrypt(sealed: ByteArray, pw: CharArray = password): ByteArray =
        BackupCrypto.decrypt(pw, ByteArrayInputStream(sealed)).use { it.readBytes() }

    @Test
    fun `round trips empty, small and multi-chunk data`() {
        val sizes = listOf(0, 1, 1000, BackupCrypto.CHUNK_SIZE, BackupCrypto.CHUNK_SIZE * 2 + 123)
        for (size in sizes) {
            val data = Random(size).nextBytes(size)
            assertArrayEquals("size $size", data, decrypt(encrypt(data)))
        }
    }

    @Test
    fun `a wrong password is reported as such`() {
        val sealed = encrypt("Ana Reyes".toByteArray())
        assertThrows(WrongBackupPasswordException::class.java) { decrypt(sealed, "rose-showrooms".toCharArray()) }
    }

    @Test
    fun `another file is not a backup`() {
        assertThrows(NotABackupException::class.java) { decrypt("PK\u0003\u0004 a zip file".toByteArray()) }
        assertThrows(NotABackupException::class.java) { decrypt(ByteArray(0)) }
    }

    @Test
    fun `a file cut short is damaged`() {
        val sealed = encrypt(Random(1).nextBytes(BackupCrypto.CHUNK_SIZE + 500))
        assertThrows(DamagedBackupException::class.java) { decrypt(sealed.copyOf(sealed.size - 10)) }
    }

    @Test
    fun `a file missing its whole last chunk is damaged`() {
        val data = Random(2).nextBytes(BackupCrypto.CHUNK_SIZE + 500)
        val sealed = encrypt(data)
        // The last chunk is its flag, length and 500 bytes plus a 16-byte tag.
        val withoutLast = sealed.copyOf(sealed.size - (1 + 4 + 500 + 16))
        assertThrows(DamagedBackupException::class.java) { decrypt(withoutLast) }
    }

    @Test
    fun `a changed byte in a later chunk is damaged, not a wrong password`() {
        val sealed = encrypt(Random(3).nextBytes(BackupCrypto.CHUNK_SIZE + 500))
        sealed[sealed.size - 20] = (sealed[sealed.size - 20].toInt() xor 1).toByte()
        assertThrows(DamagedBackupException::class.java) { decrypt(sealed) }
    }

    private companion object {
        /** The lowest accepted count, so the tests stay fast; real backups use DEFAULT_ROUNDS. */
        const val FAST_ROUNDS = 1_000
    }
}
