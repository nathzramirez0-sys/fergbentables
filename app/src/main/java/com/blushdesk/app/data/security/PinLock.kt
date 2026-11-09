package com.blushdesk.app.data.security

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** How long the app may sit in the background before it asks for the PIN again. */
enum class LockAfter(val label: String, val minutes: Long) {
    ONE_MINUTE("1 min", 1),
    FIVE_MINUTES("5 min", 5),
    FIFTEEN_MINUTES("15 min", 15),
}

data class PinState(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val lockAfter: LockAfter = LockAfter.FIVE_MINUTES,
    /** Digits in the PIN, so the lock screen can check it as soon as the last one is typed. */
    val length: Int = 0,
    /** Set after too many wrong tries: the lock screen waits until then. */
    val retryAt: Instant? = null,
)

/** Hashing and the PIN rule, kept apart from storage so they can be tested on their own. */
object PinRules {
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 6
    const val ROUNDS = 120_000
    const val MAX_TRIES = 5
    val WAIT: Duration = Duration.ofSeconds(30)

    /** Why [pin] and its [confirmation] can't be used, or null when they can. */
    fun problem(pin: String, confirmation: String): String? = when {
        pin.length !in MIN_DIGITS..MAX_DIGITS || !pin.all(Char::isDigit) -> "Use $MIN_DIGITS to $MAX_DIGITS digits."
        pin != confirmation -> "The two PINs don't match."
        else -> null
    }

    fun hash(pin: String, salt: ByteArray, rounds: Int = ROUNDS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Compares in constant time, so how long a wrong PIN takes says nothing about how close it was. */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray, rounds: Int = ROUNDS): Boolean =
        MessageDigest.isEqual(hash(pin, salt, rounds), expected)

    /** Whether time spent in the background since [leftAt] reaches [lockAfter]. */
    fun shouldLock(leftAt: Instant?, now: Instant, lockAfter: LockAfter): Boolean =
        leftAt != null && Duration.between(leftAt, now) >= Duration.ofMinutes(lockAfter.minutes)
}

/**
 * The optional PIN that keeps buyers' names and numbers private on an open counter. Only a salted
 * PBKDF2 hash of the PIN is stored, in the app's private preferences. The app locks when it starts
 * and when it comes back after [PinState.lockAfter] in the background. After [PinRules.MAX_TRIES]
 * wrong PINs it waits [PinRules.WAIT] before taking another.
 *
 * A forgotten PIN can't be recovered: clearing the app's data removes it along with the records,
 * which then come back from a backup.
 */
class PinLock(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<PinState> = _state.asStateFlow()

    private var leftAt: Instant? = null
    private var wrongTries = 0

    /** Turns the lock on, or changes the PIN. */
    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        prefs.edit {
            putString(KEY_SALT, encode(salt))
            putString(KEY_HASH, encode(PinRules.hash(pin, salt)))
            putInt(KEY_LENGTH, pin.length)
        }
        _state.update { it.copy(enabled = true, locked = false, length = pin.length) }
    }

    fun turnOff() {
        prefs.edit {
            remove(KEY_SALT)
            remove(KEY_HASH)
            remove(KEY_LENGTH)
        }
        _state.update { it.copy(enabled = false, locked = false, length = 0, retryAt = null) }
    }

    fun setLockAfter(lockAfter: LockAfter) {
        prefs.edit { putString(KEY_AFTER, lockAfter.name) }
        _state.update { it.copy(lockAfter = lockAfter) }
    }

    /** Checks [pin] without changing anything (the settings ask for it before a change). */
    fun check(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let(::decode) ?: return false
        val hash = prefs.getString(KEY_HASH, null)?.let(::decode) ?: return false
        return PinRules.matches(pin, salt, hash)
    }

    /** Unlocks with [pin]. Counts wrong tries and makes the next one wait after too many. */
    fun unlock(pin: String, now: Instant = Instant.now()): Boolean {
        val retryAt = _state.value.retryAt
        if (retryAt != null && now.isBefore(retryAt)) return false
        if (check(pin)) {
            wrongTries = 0
            _state.update { it.copy(locked = false, retryAt = null) }
            return true
        }
        wrongTries++
        if (wrongTries >= PinRules.MAX_TRIES) {
            wrongTries = 0
            _state.update { it.copy(retryAt = now + PinRules.WAIT) }
        }
        return false
    }

    fun onBackground(now: Instant = Instant.now()) {
        leftAt = now
    }

    fun onForeground(now: Instant = Instant.now()) {
        val state = _state.value
        if (state.enabled && PinRules.shouldLock(leftAt, now, state.lockAfter)) _state.update { it.copy(locked = true) }
        leftAt = null
    }

    private fun load(): PinState {
        val enabled = prefs.contains(KEY_HASH)
        return PinState(
            enabled = enabled,
            // The app always starts locked when a PIN is set.
            locked = enabled,
            lockAfter = prefs.getString(KEY_AFTER, null)?.let { saved -> LockAfter.entries.firstOrNull { it.name == saved } }
                ?: LockAfter.FIVE_MINUTES,
            length = prefs.getInt(KEY_LENGTH, 0),
        )
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS = "pin_lock"
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hash"
        const val KEY_LENGTH = "length"
        const val KEY_AFTER = "lockAfter"
        const val SALT_BYTES = 16
    }
}
