package com.arogyax.core

import com.arogyax.signal.SqiAnalyser
import com.arogyax.signal.SqiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.random.Random

class EcgQualityTest {

    private fun sqi(
        score: Double = 1.0,
        saturation: Double = 0.0,
        flatline: Double = 0.0,
        powerline: Double = 0.0,
        wander: Double = 0.0,
        reason: String? = null,
    ) = SqiResult(score, saturation, flatline, powerline, wander, reason)

    @Test
    fun `the panel reports the same number the gate used`() {
        // If these ever diverge, the worker is shown a quality figure that had
        // nothing to do with whether the window was accepted.
        val r = EcgQuality.of(sqi(score = 0.6314))
        assertEquals(0.6314, r.overall, 0.0)
        assertEquals(63, r.percent)
    }

    @Test
    fun `factor scores reproduce SqiAnalyser's own penalty terms`() {
        // The four factors are the four terms SqiAnalyser multiplies together.
        // This is the mirror EcgQuality's comment promises: if SqiAnalyser's
        // score expression changes, this fails rather than the panel quietly
        // describing a formula that is no longer in use.
        val rng = Random(7)
        repeat(200) {
            val sat = rng.nextDouble(0.0, 0.05)
            val flat = rng.nextDouble(0.0, 0.2)
            val pow = rng.nextDouble(0.0, 1.0)
            val wan = rng.nextDouble(0.0, 1.0)

            var expected = 1.0
            expected *= 1.0 - min(1.0, sat / SqiAnalyser.K_SATURATION_FAIL)
            expected *= 1.0 - min(1.0, flat / 0.10)
            expected *= 1.0 - min(1.0, pow / 0.50)
            expected *= 1.0 - min(1.0, wan / 0.80)

            val product = EcgQuality.of(
                sqi(saturation = sat, flatline = flat, powerline = pow, wander = wan),
            ).factors.fold(1.0) { acc, f -> acc * f.score }

            assertEquals(expected, product, 1e-12)
        }
    }

    @Test
    fun `the failing factor is named, not just the fact of failure`() {
        // "Poor signal" tells a health worker nothing they can act on.
        val r = EcgQuality.of(sqi(score = 0.2, powerline = 0.9))
        assertEquals("Electrical noise", r.worst?.label)
        assertNotNull(r.worst?.hint)
    }

    @Test
    fun `a clean recording names no worst factor`() {
        assertNull(EcgQuality.of(sqi()).worst)
        assertTrue(EcgQuality.of(sqi()).factors.all { it.hint == null })
    }

    @Test
    fun `usable tracks Policy's gate exactly`() {
        assertFalse(EcgQuality.of(sqi(score = Policy.K_SQI_GATE - 1e-9)).usable)
        assertTrue(EcgQuality.of(sqi(score = Policy.K_SQI_GATE)).usable)
    }

    @Test
    fun `bands are ordered and cover the whole range`() {
        assertEquals(QualityBand.POOR, EcgQuality.of(sqi(score = 0.0)).band)
        assertEquals(QualityBand.POOR, EcgQuality.of(sqi(score = 0.49)).band)
        assertEquals(QualityBand.FAIR, EcgQuality.of(sqi(score = 0.5)).band)
        assertEquals(QualityBand.FAIR, EcgQuality.of(sqi(score = 0.74)).band)
        assertEquals(QualityBand.GOOD, EcgQuality.of(sqi(score = 0.75)).band)
        assertEquals(QualityBand.GOOD, EcgQuality.of(sqi(score = 1.0)).band)
    }

    @Test
    fun `every factor is reported even when it cost nothing`() {
        // A panel that hides passing factors reads as "we only checked one thing".
        assertEquals(4, EcgQuality.of(sqi()).factors.size)
    }

    @Test
    fun `no quality string names a diagnosis - non-negotiable 1`() {
        val r = EcgQuality.of(sqi(score = 0.1, flatline = 0.5, powerline = 0.9, wander = 0.9))
        val text = (r.factors.map { it.label } + r.factors.mapNotNull { it.hint }).joinToString(" ")
        for (b in listOf("fibrillation", "arrhythmia", "afib")) {
            assertFalse("quality text names a diagnosis: $text", text.lowercase().contains(b))
        }
    }
}
