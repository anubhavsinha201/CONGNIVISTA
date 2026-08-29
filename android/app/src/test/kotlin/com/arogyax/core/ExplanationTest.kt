package com.arogyax.core

import com.arogyax.data.ClinicianOutcome
import com.arogyax.data.PatientHistory
import com.arogyax.data.ReferralState
import com.arogyax.data.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class ExplanationTest {
    private fun inputs(
        sqiScore: Double = 0.9,
        rrIrregularityScore: Double = 0.1,
        meanHr: Double = 72.0,
        cnnScore: Double? = null,
        pulseDeficitBpm: Double? = null,
        perfusedBeatFraction: Double? = null,
    ) = TierInputs(
        sqiScore = sqiScore,
        motionRejected = false,
        leadOffDetected = false,
        dataGapDetected = false,
        rrIntervalCount = 40,
        meanHr = meanHr,
        rrIrregularityScore = rrIrregularityScore,
        cnnScore = cnnScore,
        pulseDeficitBpm = pulseDeficitBpm,
        perfusedBeatFraction = perfusedBeatFraction,
    )

    @Test
    fun `a RETAKE leads with its reason and nothing else`() {
        val d = TierDecision(Tier.RETAKE, DecidedBy.GATE, RetakeReason.ELECTRODE_DETACHED)
        val reasons = Explainer.forDecision(d, inputs(sqiScore = 0.3))
        assertEquals(1, reasons.size)
        assertEquals("why.retake.electrode", reasons[0].key)
        assertEquals("30%", reasons[0].values["sqi"])
    }

    @Test
    fun `every RetakeReason maps to a distinct key, plus the null-reason fallback`() {
        val expected = mapOf(
            RetakeReason.ELECTRODE_DETACHED to "why.retake.electrode",
            RetakeReason.PATIENT_MOVED to "why.retake.motion",
            RetakeReason.DROPPED_DATA to "why.retake.connection",
            RetakeReason.POOR_SIGNAL_QUALITY to "why.retake.quality",
            RetakeReason.TOO_FEW_BEATS to "why.retake.tooShort",
            RetakeReason.BEAT_DETECTION_UNRELIABLE to "why.retake.beatDetection",
        )
        for ((reason, key) in expected) {
            val d = TierDecision(Tier.RETAKE, DecidedBy.GATE, reason)
            assertEquals(key, Explainer.forDecision(d, inputs())[0].key)
        }
        val noReason = TierDecision(Tier.RETAKE, DecidedBy.GATE, null)
        assertEquals("why.retake.generic", Explainer.forDecision(noReason, inputs())[0].key)
    }

    @Test
    fun `irregular rhythm leads for a non-retake tier`() {
        val d = TierDecision(Tier.YELLOW, DecidedBy.RULES)
        val reasons = Explainer.forDecision(d, inputs(rrIrregularityScore = 0.8))
        assertEquals("why.rhythm.irregular", reasons[0].key)
    }

    @Test
    fun `a CNN score at or above threshold counts as irregular even with a low rules score`() {
        val d = TierDecision(Tier.YELLOW, DecidedBy.CNN)
        val reasons = Explainer.forDecision(d, inputs(rrIrregularityScore = 0.1, cnnScore = 0.5))
        assertEquals("why.rhythm.irregular", reasons[0].key)
    }

    @Test
    fun `regular rhythm below both gates`() {
        val d = TierDecision(Tier.GREEN, DecidedBy.RULES)
        val reasons = Explainer.forDecision(d, inputs(rrIrregularityScore = 0.1))
        assertEquals("why.rhythm.regular", reasons[0].key)
    }

    @Test
    fun `every DecidedBy maps to a distinct source key`() {
        val expected = mapOf(
            DecidedBy.RULES to "why.source.rules",
            DecidedBy.CNN to "why.source.model",
            DecidedBy.RULES_AND_CNN to "why.source.both",
            DecidedBy.GATE to "why.source.gate",
            DecidedBy.HISTORY to "why.source.history",
        )
        for ((decidedBy, key) in expected) {
            val d = TierDecision(Tier.YELLOW, decidedBy)
            val reasons = Explainer.forDecision(d, inputs())
            assertTrue("$decidedBy should produce $key, got $reasons", reasons.any { it.key == key })
        }
    }

    @Test
    fun `rate reasons say which way it went, not merely that it did`() {
        val d = TierDecision(Tier.RED, DecidedBy.RULES)
        assertTrue(Explainer.forDecision(d, inputs(meanHr = 40.0)).any { it.key == "why.rate.low" })
        assertTrue(Explainer.forDecision(d, inputs(meanHr = 140.0)).any { it.key == "why.rate.high" })
        assertTrue(Explainer.forDecision(d, inputs(meanHr = 72.0)).any { it.key == "why.rate.normal" })
    }

    @Test
    fun `PPG corroboration - pulse deficit carries the rounded number`() {
        val d = TierDecision(Tier.RED, DecidedBy.RULES, ppg = PpgCorroboration.PULSE_DEFICIT)
        val reasons = Explainer.forDecision(d, inputs(pulseDeficitBpm = 11.6))
        val r = reasons.first { it.key == "why.ppg.pulseDeficit" }
        assertEquals("12", r.values["deficit"])
    }

    @Test
    fun `PPG corroboration - none adds no ppg reason at all`() {
        val d = TierDecision(Tier.YELLOW, DecidedBy.RULES, ppg = PpgCorroboration.NONE)
        val reasons = Explainer.forDecision(d, inputs())
        assertFalse(reasons.any { it.key.startsWith("why.ppg.") })
    }

    @Test
    fun `no reason for any branch contains a diagnosis word`() {
        val scenarios = listOf(
            TierDecision(Tier.RETAKE, DecidedBy.GATE, RetakeReason.ELECTRODE_DETACHED),
            TierDecision(Tier.RED, DecidedBy.RULES_AND_CNN, ppg = PpgCorroboration.PULSE_DEFICIT),
            TierDecision(Tier.GREEN, DecidedBy.RULES),
        )
        for (d in scenarios) {
            val text = Explainer.forDecision(d, inputs(pulseDeficitBpm = 10.0, cnnScore = 0.9)).joinToString()
            for (word in listOf("fibrillation", "arrhythmia", "atrial")) {
                assertFalse("'$word' leaked into a Reason for $d", text.lowercase().contains(word))
            }
        }
    }

    @Test
    fun `forRepeat leads with the repeat reason and its numbers`() {
        val now = OffsetDateTime.now()
        val history = PatientHistory(
            "patient",
            listOf(TimelineEntry("r1", now.minusDays(5), "RED", referralState = ReferralState.ACKNOWLEDGED)),
        )
        val reasons = Explainer.forRepeat(history)
        assertEquals("why.repeat.referral_open", reasons[0].key)
    }

    @Test
    fun `forRepeat omits the flag-rate reason below the burden confidence minimum`() {
        val now = OffsetDateTime.now()
        val history = PatientHistory("patient", listOf(TimelineEntry("r1", now.minusDays(5), "YELLOW")))
        val reasons = Explainer.forRepeat(history)
        assertFalse(reasons.any { it.key == "why.history.flagRate" })
    }

    @Test
    fun `forRepeat includes the flag-rate reason once confidence is usable`() {
        val now = OffsetDateTime.now()
        val history = PatientHistory(
            "patient",
            listOf(
                TimelineEntry("r1", now.minusDays(240), "GREEN"),
                TimelineEntry("r2", now.minusDays(150), "YELLOW"),
                TimelineEntry("r3", now.minusDays(60), "YELLOW"),
            ),
        )
        val reasons = Explainer.forRepeat(history)
        assertTrue(reasons.any { it.key == "why.history.flagRate" })
    }

    @Test
    fun `EXPLANATION_KEYS covers every key this test has observed Explainer emit`() {
        val observed = mutableSetOf<String>()
        for (reason in RetakeReason.values().toList() + listOf(null)) {
            observed += Explainer.forDecision(TierDecision(Tier.RETAKE, DecidedBy.GATE, reason), inputs())
                .map { it.key }
        }
        for (decidedBy in DecidedBy.values()) {
            observed += Explainer.forDecision(TierDecision(Tier.YELLOW, decidedBy), inputs(meanHr = 40.0))
                .map { it.key }
            observed += Explainer.forDecision(TierDecision(Tier.YELLOW, decidedBy), inputs(meanHr = 140.0))
                .map { it.key }
            observed += Explainer.forDecision(TierDecision(Tier.YELLOW, decidedBy), inputs(rrIrregularityScore = 0.1))
                .map { it.key }
        }
        for (ppg in PpgCorroboration.values()) {
            observed += Explainer.forDecision(TierDecision(Tier.RED, DecidedBy.RULES, ppg = ppg), inputs()).map { it.key }
        }
        assertTrue("observed a key EXPLANATION_KEYS doesn't cover: ${observed - EXPLANATION_KEYS}", observed.all { it in EXPLANATION_KEYS })
    }
}
