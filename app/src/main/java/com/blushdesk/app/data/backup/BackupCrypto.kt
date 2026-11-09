package com.blushdesk.app.data.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The file is not a FergBentables backup at all. */
class NotABackupException : IOException("This file is not a FergBentables backup.")

/** The password does not open the file. */
class WrongBackupPasswordException : IOException("That password does not open this backup.")

/** The file is a backup, but damaged or cut short. */
class DamagedBackupException(message: String = "This backup file is damaged or incomplete.") : IOException(message)

/**
 * Password protection for backup files.
 *
 * The key comes from the password through PBKDF2 (HMAC-SHA256, a random salt, many rounds), and the
 * data is encrypted with AES-256-GCM in 1 MB chunks, so a backup with many photos never has to sit
 * in memory whole. Layout:
 *
 * - header: "FBBACKUP", format version (1 byte), PBKDF2 rounds (int), salt (16 bytes), nonce prefix (8 bytes)
 * - then each chunk: last-chunk flag (1 byte), ciphertext length (int), ciphertext with its 16-byte tag
 *
 * Chunk i uses the nonce prefix + i as its nonce and authenticates the header, i and its flag, so
 * chunks cannot be swapped, dropped or cut off at the end without the file failing to open.
 */
object BackupCrypto {
    const val DEFAULT_ROUNDS = 150_000
    const val CHUNK_SIZE = 1 shl 20

    private val MAGIC = "FBBACKUP".toByteArray(Charsets.US_ASCII)
    private const val FORMAT: Byte = 1
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 8
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val MIN_ROUNDS = 1_000
    private const val MAX_ROUNDS = 10_000_000

    /** Everything written to the returned stream is encrypted into [output]; closing it closes [output]. */
    fun encrypt(
        password: CharArray,
        output: OutputStream,
        rounds: Int = DEFAULT_ROUNDS,
        random: SecureRandom = SecureRandom(),
    ): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = header(rounds, salt, prefix)
        output.write(header)
        return EncryptingStream(DataOutputStream(output), deriveKey(password, salt, rounds), header, prefix)
    }

    /**
     * Reads [input]'s header and returns a stream of the decrypted data. Throws [NotABackupException]
     * for any other file; reading throws [WrongBackupPasswordException] or [DamagedBackupException].
     */
    fun decrypt(password: CharArray, input: InputStream): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
            if (!magic.contentEquals(MAGIC) || data.readByte() != FORMAT) throw NotABackupException()
            val rounds = data.readInt()
            if (rounds !in MIN_ROUNDS..MAX_ROUNDS) throw NotABackupException()
            val salt = ByteArray(SALT_BYTES).also(data::readFully)
            val prefix = ByteArray(PREFIX_BYTES).also(data::readFully)
            return DecryptingStream(data, deriveKey(password, salt, rounds), header(rounds, salt, prefix), prefix)
        } catch (e: EOFException) {
            throw NotABackupException()
        }
    }

    private fun header(rounds: Int, salt: ByteArray, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 1 + 4 + SALT_BYTES + PREFIX_BYTES)
            .put(MAGIC).put(FORMAT).putInt(rounds).put(salt).put(prefix)
            .array()

    private fun deriveKey(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun chunkCipher(mode: Int, key: SecretKeySpec, header: ByteArray, prefix: ByteArray, index: Int, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(PREFIX_BYTES + 4).put(prefix).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(5).putInt(index).put(if (last) 1 else 0).array())
        }
    }

    private class EncryptingStream(
        private val out: DataOutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : OutputStream() {
        private val buffer = ByteArray(CHUNK_SIZE)
        private var filled = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "Stream is closed" }
            var from = off
            var left = len
            while (left > 0) {
                // A full buffer is only sent once more data arrives, so the last chunk is always known.
                if (filled == buffer.size) emit(last = false)
                val n = minOf(left, buffer.size - filled)
                System.arraycopy(b, from, buffer, filled, n)
                filled += n
                from += n
                left -= n
            }
        }

        override fun close() {
            if (closed) return
            emit(last = true)
            closed = true
            out.close()
        }

        private fun emit(last: Boolean) {
            val sealed = chunkCipher(Cipher.ENCRYPT_MODE, key, header, prefix, index, last).doFinal(buffer, 0, filled)
            out.writeByte(if (last) 1 else 0)
            out.writeInt(sealed.size)
            out.write(sealed)
            index++
            filled = 0
        }
    }

    private class DecryptingStream(
        private val input: DataInputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var position = 0
        private var index = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == plain.size) {
                if (finished) return -1
                next()
            }
            val n = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, n)
            position += n
            return n
        }

        override fun close() = input.close()

        private fun next() {
            val last: Boolean
            val sealed: ByteArray
            try {
                last = when (input.readByte().toInt()) {
                    0 -> false
                    1 -> true
                    else -> throw DamagedBackupException()
                }
                val length = input.readInt()
                if (length < TAG_BITS / 8 || length > CHUNK_SIZE + TAG_BITS / 8) throw DamagedBackupException()
                sealed = ByteArray(length).also(input::readFully)
            } catch (e: EOFException) {
                throw DamagedBackupException()
            }
            plain = try {
                chunkCipher(Cipher.DECRYPT_MODE, key, header, prefix, index, last).doFinal(sealed)
            } catch (e: AEADBadTagException) {
                // The first chunk failing almost always means a mistyped password.
                if (index == 0) throw WrongBackupPasswordException() else throw DamagedBackupException()
            }
            position = 0
            index++
            finished = last
        }
    }
}
