package com.arogyax.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors ml/reference/validate_record.py section 5 (pseudo-ID behaviour). */
class PseudoIdTest {
    private val salt = "tn-coimbatore-2026"
    private val rawId = "TN-1234 5678"

    @Test
    fun `the same patient on two phones with one deployment salt collides`() {
        assertEquals(PseudoId.derive(rawId, salt), PseudoId.derive("tn12345678", salt))
    }

    @Test
    fun `a different deployment gets a different value`() {
        assertNotEquals(PseudoId.derive(rawId, salt), PseudoId.derive(rawId, "tn-salem-2026"))
    }

    @Test
    fun `different patients do not collide`() {
        assertNotEquals(
            PseudoId.derive("TN-1111 1111", salt),
            PseudoId.derive("TN-2222 2222", salt),
        )
    }

    @Test
    fun `an empty salt is refused`() {
        assertThrows(IllegalArgumentException::class.java) { PseudoId.derive(rawId, "") }
    }

    @Test
    fun `the derived pseudo-ID satisfies minLength`() {
        assertTrue(PseudoId.derive(rawId, salt).length >= 8)
    }

    @Test
    fun `the raw identifier appears nowhere in the derived value`() {
        val pid = PseudoId.derive(rawId, salt)
        assertTrue(!pid.uppercase().contains(PseudoId.normalise(rawId)))
    }
}
