package com.arogyax.core

import com.arogyax.signal.SqiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRepeatTest {

    private fun quality(
        flatline: Double = 0.0,
        saturation: Double = 0.0,
        powerline: Double = 0.0,
        wander: Double = 0.0,
    ) = EcgQuality.of(SqiResult(0.3, saturation, flatline, powerline, wander, null))

    @Test
    fun `the loop terminates - attempt 3 stops asking`() {
        // The single most important property here. A worker asked to retake
        // indefinitely will either abandon the patient or record noise to make
        // the app stop, and neither is a screening result.
        val g = AdaptiveRepeat.guide(RetakeReason.PATIENT_MOVED, attempt = 3)
        assertEquals(RepeatAction.ESCALATE, g.action)
        assertFalse(g.shouldCaptureAgain)
        assertEquals(0, g.attemptsRemaining)
    }

    @Test
    fun `no reason can produce another capture past the limit`() {
        for (reason in RetakeReason.entries) {
            for (attempt in AdaptiveRepeat.MAX_ATTEMPTS..(AdaptiveRepeat.MAX_ATTEMPTS + 3)) {
                val g = AdaptiveRepeat.guide(reason, attempt)
                assertFalse(
                    "$reason at attempt $attempt still asked for another capture",
                    g.shouldCaptureAgain,
                )
            }
        }
    }

    @Test
    fun `the attempt before the limit warns that it is the last one`() {
        val g = AdaptiveRepeat.guide(RetakeReason.PATIENT_MOVED, attempt = 2)
        assertEquals(RepeatAction.REPEAT_FINAL, g.action)
        assertTrue(g.shouldCaptureAgain)
        assertTrue(g.instruction.contains("last attempt"))
    }

    @Test
    fun `each gate gets its own targeted instruction`() {
        // The point of section 13: the reason the window was refused determines
        // what the worker is asked to change.
        val keys = RetakeReason.entries.associateWith {
            AdaptiveRepeat.guide(it, attempt = 1).instructionKey
        }
        assertEquals("repeat_motion", keys[RetakeReason.PATIENT_MOVED])
        assertEquals("repeat_contact", keys[RetakeReason.ELECTRODE_DETACHED])
        assertEquals("repeat_connection", keys[RetakeReason.DROPPED_DATA])
        assertEquals("repeat_too_short", keys[RetakeReason.TOO_FEW_BEATS])
        assertEquals("repeat_beat_detection", keys[RetakeReason.BEAT_DETECTION_UNRELIABLE])
    }

    @Test
    fun `poor signal quality is refined by whichever factor actually failed`() {
        // POOR_SIGNAL_QUALITY on its own is the one reason that does not say
        // what to fix, so the quality panel supplies it.
        fun keyFor(q: EcgQualityReport) =
            AdaptiveRepeat.guide(RetakeReason.POOR_SIGNAL_QUALITY, 1, q).instructionKey

        assertEquals("repeat_contact", keyFor(quality(flatline = 0.9)))
        assertEquals("repeat_amplitude", keyFor(quality(saturation = 0.9)))
        assertEquals("repeat_noise", keyFor(quality(powerline = 0.9)))
        assertEquals("repeat_motion", keyFor(quality(wander = 0.9)))
    }

    @Test
    fun `poor signal with no quality panel still gives an actionable instruction`() {
        val g = AdaptiveRepeat.guide(RetakeReason.POOR_SIGNAL_QUALITY, 1, null)
        assertEquals("repeat_generic", g.instructionKey)
        assertTrue(g.instruction.isNotBlank())
    }

    @Test
    fun `every instruction is a non-empty imperative that does not name a diagnosis`() {
        for (reason in RetakeReason.entries) {
            for (attempt in 1..AdaptiveRepeat.MAX_ATTEMPTS) {
                val g = AdaptiveRepeat.guide(reason, attempt)
                assertTrue("$reason/$attempt: empty instruction", g.instruction.isNotBlank())
                assertTrue("$reason/$attempt: no key", g.instructionKey.isNotBlank())
                val lower = g.instruction.lowercase()
                for (b in listOf("fibrillation", "arrhythmia", "afib")) {
                    assertFalse("$reason/$attempt names a diagnosis", lower.contains(b))
                }
            }
        }
    }

    @Test
    fun `attempt must be one-based`() {
        for (bad in listOf(0, -1)) {
            try {
                AdaptiveRepeat.guide(RetakeReason.PATIENT_MOVED, bad)
                throw AssertionError("attempt $bad should have been rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("1-based"))
            }
        }
    }

    @Test
    fun `the escalation message tells the worker what to do instead of retrying`() {
        // Escalating must not be a dead end - the patient still needs an action.
        val g = AdaptiveRepeat.guide(RetakeReason.POOR_SIGNAL_QUALITY, AdaptiveRepeat.MAX_ATTEMPTS)
        assertTrue(g.instruction.contains("PHC"))
        assertNotEquals("repeat_generic", g.instructionKey)
    }
}
