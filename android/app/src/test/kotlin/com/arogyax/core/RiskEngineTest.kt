package com.arogyax.core

import com.arogyax.data.ClinicianOutcome
import com.arogyax.data.PatientHistory
import com.arogyax.data.ReferralState
import com.arogyax.data.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class RiskEngineTest {

    private val now: OffsetDateTime = OffsetDateTime.now()

    /** Newest first, matching PatientHistory's contract. */
    private fun history(
        vararg tiers: String,
        hr: List<Double?> = emptyList(),
        referral: ReferralState? = null,
        outcome: ClinicianOutcome? = null,
        dayStep: Long = 30,
    ) = PatientHistory(
        patientPseudoId = "p-test",
        timeline = tiers.mapIndexed { idx, t ->
            TimelineEntry(
                recordId = "r$idx",
                capturedAt = now.minusDays(idx * dayStep),
                tier = t,
                meanHr = hr.getOrNull(idx),
                referralState = referral,
                outcome = outcome,
            )
        },
    )

    private fun decision(tier: Tier) = TierDecision(
        tier = tier,
        decidedBy = if (tier == Tier.RETAKE) DecidedBy.GATE else DecidedBy.RULES,
    )

    // ---- The safety property, asserted exhaustively -------------------------

    @Test
    fun `history can never lower the priority this visit's tier already earned`() {
        // The whole reason RiskEngine is allowed to exist. Enumerated over every
        // tier crossed with every history shape that reaches a different branch,
        // because a single worked example would not catch a mis-ordered
        // escalateOnly() in one arm.
        val histories = mapOf(
            "empty" to history(),
            "all clean" to history("GREEN", "GREEN", "GREEN"),
            "intermittent" to history("GREEN", "YELLOW", "GREEN", "YELLOW"),
            "persistent" to history("RED", "ORANGE", "YELLOW"),
            "open referral" to history("YELLOW", "GREEN", referral = ReferralState.ACKNOWLEDGED),
            "confirmed" to history("RED", "GREEN", "GREEN", outcome = ClinicianOutcome.CONFIRMED),
            "repeatedly suspicious" to history("RED", "ORANGE", "YELLOW", "GREEN"),
            "improving" to history("GREEN", "GREEN", "YELLOW", "RED"),
            "retakes mixed in" to history("RETAKE", "YELLOW", "RETAKE", "GREEN"),
        )

        for (tier in Tier.entries) {
            val floor = RiskEngine.priorityOf(tier)
            for ((label, h) in histories) {
                val got = RiskEngine.assess(decision(tier), h).priority
                assertTrue(
                    "history '$label' downgraded $tier from $floor to $got",
                    got.ordinal >= floor.ordinal,
                )
            }
        }
    }

    @Test
    fun `escalateOnly is a max, in both argument orders`() {
        for (a in ScreeningPriority.entries) {
            for (b in ScreeningPriority.entries) {
                val expected = if (a.ordinal >= b.ordinal) a else b
                assertEquals(expected, RiskEngine.escalateOnly(a, b))
                assertEquals(expected, RiskEngine.escalateOnly(b, a))
            }
        }
    }

    // ---- A refused window is not evidence ----------------------------------

    @Test
    fun `a RETAKE stays a repeat no matter how alarming the history is`() {
        // The correct next action after a refused capture is still "capture
        // again". Letting history promote silence into a referral would mean
        // referring on the basis of a window we explicitly declined to score.
        val alarming = history("RED", "RED", "RED", referral = ReferralState.ACKNOWLEDGED)
        val a = RiskEngine.assess(decision(Tier.RETAKE), alarming)
        assertEquals(ScreeningPriority.REPEAT, a.priority)
        assertFalse(a.raisedByHistory)
        assertEquals(listOf("risk_retake_not_scored"), a.reasons.map { it.key })
    }

    // ---- Escalation actually happens ---------------------------------------

    @Test
    fun `a lapsed referral raises a clean visit to priority review`() {
        val lapsed = PatientHistory(
            "p-lapsed",
            listOf(
                TimelineEntry("r0", now.minusDays(1), "GREEN"),
                // Flagged, never referred, long enough ago to have lapsed.
                TimelineEntry("r1", now.minusDays(400), "RED", referralState = null),
            ),
        )
        val a = RiskEngine.assess(decision(Tier.GREEN), lapsed)
        assertEquals(ScreeningPriority.PRIORITY_REVIEW, a.priority)
        assertTrue(a.raisedByHistory)
        assertTrue(a.reasons.any { it.key == "risk_referral_lapsed" })
    }

    @Test
    fun `three consecutive flagged visits read as repeatedly suspicious`() {
        val h = history("YELLOW", "ORANGE", "RED", "GREEN")
        assertEquals(RiskTrajectory.REPEATEDLY_SUSPICIOUS, RiskEngine.trajectoryOf(h))
    }

    @Test
    fun `too little history is reported as insufficient, not as stable`() {
        // "Stable" is a claim. Two visits do not support it, and a worker
        // reading "stable" would take it as reassurance.
        assertEquals(RiskTrajectory.INSUFFICIENT_DATA, RiskEngine.trajectoryOf(history()))
        assertEquals(
            RiskTrajectory.INSUFFICIENT_DATA,
            RiskEngine.trajectoryOf(history("GREEN", "GREEN")),
        )
    }

    @Test
    fun `an all-clean history is stable and adds nothing`() {
        val a = RiskEngine.assess(decision(Tier.GREEN), history("GREEN", "GREEN", "GREEN"))
        assertEquals(RiskTrajectory.STABLE, a.trajectory)
        assertEquals(ScreeningPriority.ROUTINE, a.priority)
        assertFalse(a.raisedByHistory)
    }

    @Test
    fun `flagging becoming more frequent reads as increasing`() {
        // newest-first: recent half flagged, older half clean
        assertEquals(
            RiskTrajectory.INCREASING,
            RiskEngine.trajectoryOf(history("YELLOW", "RED", "GREEN", "GREEN")),
        )
    }

    @Test
    fun `flagging becoming less frequent reads as improving`() {
        assertEquals(
            RiskTrajectory.IMPROVING,
            RiskEngine.trajectoryOf(history("GREEN", "GREEN", "YELLOW", "RED")),
        )
    }

    // ---- Personal baseline (spec 2.6) --------------------------------------

    @Test
    fun `baseline deviation is reported when the rate departs from the patient's own history`() {
        val h = history("GREEN", "GREEN", "GREEN", hr = listOf(101.0, 70.0, 72.0))
        val r = RiskEngine.baselineDeviation(h)
        assertEquals("risk_baseline_hr_above", r?.key)
        assertEquals("101", r?.values?.get("current"))
        assertEquals("71", r?.values?.get("baseline"))
    }

    @Test
    fun `baseline needs enough history before it claims anything`() {
        assertNull(RiskEngine.baselineDeviation(history("GREEN", hr = listOf(140.0))))
        assertNull(
            RiskEngine.baselineDeviation(history("GREEN", "GREEN", hr = listOf(140.0, 70.0))),
        )
    }

    @Test
    fun `a rate close to the patient's own baseline reports no deviation`() {
        val h = history("GREEN", "GREEN", "GREEN", hr = listOf(74.0, 72.0, 70.0))
        assertNull(RiskEngine.baselineDeviation(h))
    }

    @Test
    fun `the personal baseline never changes the priority - spec 2_6`() {
        // "Must not suppress a clinically important abnormality solely because
        // the value is close to a patient's historical pattern." A patient whose
        // rate has always been high must still be escalated by this visit's
        // tier; the baseline may only annotate.
        val alwaysFast = history("GREEN", "GREEN", "GREEN", hr = listOf(150.0, 148.0, 152.0))
        val a = RiskEngine.assess(decision(Tier.RED), alwaysFast)
        assertEquals(ScreeningPriority.PRIORITY_REVIEW, a.priority)
        assertNull(
            "a rate matching the patient's own history must not add a deviation reason",
            RiskEngine.baselineDeviation(alwaysFast),
        )
    }

    // ---- Non-negotiables ---------------------------------------------------

    @Test
    fun `no reason key names a diagnosis - non-negotiables 1 and 7`() {
        val histories = listOf(
            history(),
            history("GREEN", "YELLOW", "GREEN", "YELLOW", hr = listOf(120.0, 70.0, 71.0, 69.0)),
            history("RED", "RED", "RED", referral = ReferralState.ACKNOWLEDGED),
            history("RED", "GREEN", "GREEN", outcome = ClinicianOutcome.CONFIRMED),
        )
        val banned = listOf("fibrillation", "arrhythmia", "afib")
        for (tier in Tier.entries) {
            for (h in histories) {
                for (r in RiskEngine.assess(decision(tier), h).reasons) {
                    val key = r.key.lowercase()
                    for (b in banned) {
                        assertFalse("reason key names a diagnosis: ${r.key}", key.contains(b))
                    }
                    // Keys are looked up in a static table, so they must be
                    // stable identifiers rather than prose.
                    assertTrue("reason key is not a stable id: ${r.key}", key.matches(Regex("[a-z0-9_]+")))
                }
            }
        }
    }

    @Test
    fun `every assessment carries at least one reason`() {
        // A priority with no stated reason is exactly the black box the
        // explainability section exists to prevent.
        for (tier in Tier.entries) {
            val a = RiskEngine.assess(decision(tier), history("GREEN", "GREEN", "GREEN"))
            assertTrue("$tier produced no reasons", a.reasons.isNotEmpty())
        }
    }
}
