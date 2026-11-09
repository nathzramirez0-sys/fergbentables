package com.blushdesk.app.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PinRulesTest {

    @Test
    fun `a PIN is 4 to 6 digits and typed the same twice`() {
        assertEquals("Use 4 to 6 digits.", PinRules.problem("123", "123"))
        assertEquals("Use 4 to 6 digits.", PinRules.problem("1234567", "1234567"))
        assertEquals("Use 4 to 6 digits.", PinRules.problem("12a4", "12a4"))
        assertEquals("The two PINs don't match.", PinRules.problem("2468", "2469"))
        assertNull(PinRules.problem("2468", "2468"))
        assertNull(PinRules.problem("135790", "135790"))
    }

    @Test
    fun `only the right PIN matches its salted hash`() {
        val salt = ByteArray(16) { it.toByte() }
        val hash = PinRules.hash("2468", salt, rounds = FAST)
        assertTrue(PinRules.matches("2468", salt, hash, rounds = FAST))
        assertFalse(PinRules.matches("2469", salt, hash, rounds = FAST))
        // The same PIN with another salt hashes differently, so equal PINs don't look equal.
        assertFalse(PinRules.hash("2468", ByteArray(16), rounds = FAST).contentEquals(hash))
    }

    @Test
    fun `the app locks once it has been away for the chosen time`() {
        val left = Instant.parse("2026-10-20T10:00:00Z")
        assertFalse(PinRules.shouldLock(null, left, LockAfter.ONE_MINUTE))
        assertFalse(PinRules.shouldLock(left, left.plusSeconds(59), LockAfter.ONE_MINUTE))
        assertTrue(PinRules.shouldLock(left, left.plusSeconds(60), LockAfter.ONE_MINUTE))
        assertFalse(PinRules.shouldLock(left, left.plusSeconds(4 * 60), LockAfter.FIVE_MINUTES))
        assertTrue(PinRules.shouldLock(left, left.plusSeconds(15 * 60), LockAfter.FIFTEEN_MINUTES))
    }

    private companion object {
        const val FAST = 1_000
    }
}
