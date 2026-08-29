package com.arogyax.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * Mirrors ml/reference/validate_history.py's checks exactly (same scenarios,
 * same names) - that script is itself the mirror of app/lib/data/patient_history.dart
 * this port is checked against, so matching its checks one-to-one is what
 * keeps three implementations (Dart source, Python mirror, this Kotlin port)
 * from silently drifting apart.
 *
 * validate_history.py can hardcode a fixed NOW because it is a fully
 * independent reimplementation. PatientHistory here is a faithful port of
 * patient_history.dart, which calls the real `DateTime.now()` (non-injectable,
 * on purpose - this is production behavior, not a testability gap to paper
 * over), so [now] below is captured for REAL at test time and every fixture
 * is built relative to it - not a hardcoded literal date, which would drift
 * out of sync with the class's own internal `OffsetDateTime.now()` call by
 * however many days separate the literal from whenever the test actually runs.
 */
class PatientHistoryTest {
    private val now: OffsetDateTime = OffsetDateTime.now()

    private fun entry(
        daysAgo: Long,
        tier: String,
        referralState: ReferralState? = null,
        outcome: ClinicianOutcome? = null,
    ) = TimelineEntry(
        recordId = "rec-$daysAgo-$tier",
        capturedAt = now.minusDays(daysAgo),
        tier = tier,
        referralState = referralState,
        outcome = outcome,
    )

    private fun history(vararg entries: TimelineEntry) =
        PatientHistory("patient", entries.sortedByDescending { it.capturedAt })

    @Test
    fun `feature 5 - the timeline from the notebook page`() {
        // Jan normal, Feb irregular, Mar suspicious -> PHC, Apr suspicious -> referred.
        val notebook = history(
            entry(240, "GREEN"),
            entry(210, "YELLOW"),
            entry(180, "YELLOW", referralState = ReferralState.SEEN_AT_PHC),
            entry(150, "RED", referralState = ReferralState.ACKNOWLEDGED),
        )
        assertEquals(150L, java.time.temporal.ChronoUnit.DAYS.between(notebook.timeline[0].capturedAt, now))
        assertEquals(4, notebook.scored.size)
        assertEquals("RED", notebook.worstTier)
    }

    @Test
    fun `feature 4 - flag rate and intermittency`() {
        val notebook = history(
            entry(240, "GREEN"),
            entry(210, "YELLOW"),
            entry(180, "YELLOW", referralState = ReferralState.SEEN_AT_PHC),
            entry(150, "RED", referralState = ReferralState.ACKNOWLEDGED),
        )
        assertEquals(0.75, notebook.flagRate, 1e-9)
        assertTrue(notebook.isIntermittent)

        // A RETAKE is missing data, not a negative. Counting it as one deflates the rate.
        val withRetake = history(entry(30, "YELLOW"), entry(20, "RETAKE"), entry(10, "YELLOW"))
        assertEquals(1.0, withRetake.flagRate, 1e-9)
        assertFalse("only 2 scored visits, below the burden minimum", withRetake.isPersistent)
        assertFalse(withRetake.isIntermittent)
    }

    @Test
    fun `confidence gating - refusing to quote a rate too early`() {
        val two = history(entry(10, "YELLOW"), entry(3, "GREEN"))
        assertEquals(BurdenConfidence.INSUFFICIENT, two.burdenConfidence)

        val tight = history(entry(6, "YELLOW"), entry(3, "GREEN"), entry(1, "YELLOW"))
        assertEquals(BurdenConfidence.PROVISIONAL, tight.burdenConfidence)

        val notebook = history(
            entry(240, "GREEN"), entry(210, "YELLOW"),
            entry(180, "YELLOW", referralState = ReferralState.SEEN_AT_PHC),
            entry(150, "RED", referralState = ReferralState.ACKNOWLEDGED),
        )
        assertEquals(BurdenConfidence.USABLE, notebook.burdenConfidence)
    }

    @Test
    fun `feature 12 - adaptive repeat interval`() {
        assertEquals(0, history().recommendedRepeatDays)
        assertEquals(PatientHistory.INTERVAL_ROUTINE, history(entry(5, "GREEN")).recommendedRepeatDays)
        assertEquals(PatientHistory.INTERVAL_AFTER_RED, history(entry(5, "RED")).recommendedRepeatDays)
        assertEquals(
            "soonest after a RETAKE - nothing was learned",
            PatientHistory.INTERVAL_AFTER_RETAKE,
            history(entry(5, "RETAKE")).recommendedRepeatDays,
        )

        // open referral outranks intermittency
        val notebook = history(
            entry(240, "GREEN"), entry(210, "YELLOW"),
            entry(180, "YELLOW", referralState = ReferralState.SEEN_AT_PHC),
            entry(150, "RED", referralState = ReferralState.ACKNOWLEDGED),
        )
        assertEquals(PatientHistory.INTERVAL_OPEN_REFERRAL, notebook.recommendedRepeatDays)

        val interm = history(entry(90, "YELLOW"), entry(60, "GREEN"), entry(30, "YELLOW"))
        assertEquals(PatientHistory.INTERVAL_INTERMITTENT, interm.recommendedRepeatDays)
        assertEquals("varies_between_visits", interm.repeatReasonKey)

        val confirmed = history(
            entry(40, "RED", referralState = ReferralState.CLOSED, outcome = ClinicianOutcome.CONFIRMED),
        )
        assertEquals(
            "confirmed case moves to follow-up, not re-screening",
            PatientHistory.INTERVAL_CONFIRMED,
            confirmed.recommendedRepeatDays,
        )
        assertEquals("under_clinician_care", confirmed.repeatReasonKey)
    }

    @Test
    fun `feature 12b - ORANGE, repeated across visits`() {
        val orangeWorst = history(entry(60, "YELLOW"), entry(40, "ORANGE"), entry(20, "ORANGE"))
        assertEquals("ORANGE outranks YELLOW as the worst tier", "ORANGE", orangeWorst.worstTier)
        assertTrue(
            "ORANGE gets its own repeat interval, between RED and YELLOW",
            PatientHistory.INTERVAL_AFTER_RED < PatientHistory.INTERVAL_AFTER_ORANGE &&
                PatientHistory.INTERVAL_AFTER_ORANGE < PatientHistory.INTERVAL_AFTER_YELLOW,
        )
        assertEquals(PatientHistory.INTERVAL_AFTER_ORANGE, orangeWorst.recommendedRepeatDays)
        assertEquals("previous_repeated_finding", orangeWorst.repeatReasonKey)
    }

    @Test
    fun `due list and lapsed referrals`() {
        // A day-count expectation, not a range, would be flaky by design here
        // - not from slow test execution, from correctness. Real elapsed time
        // between building the fixture (this test's `now`) and the class's
        // own internal `OffsetDateTime.now()` call is always positive, which
        // truncating day-math always rounds DOWN, never up - so "exactly 175"
        // becomes "174" the instant any nonzero time has passed, which it
        // always has. Assert the pair truncation can produce, not one literal.
        assertTrue(history(entry(200, "GREEN")).daysUntilDue in -20L..-19L)
        assertTrue(history(entry(5, "GREEN")).daysUntilDue in 174L..175L)

        val lapsed = history(entry(40, "RED", referralState = ReferralState.NONE))
        assertTrue("a flagged visit nobody acted on is lapsed", lapsed.hasLapsedReferral)

        val acted = history(entry(40, "RED", referralState = ReferralState.SEEN_AT_PHC))
        assertFalse("an acted-on referral is not lapsed", acted.hasLapsedReferral)

        assertFalse(
            "a GREEN visit is never lapsed",
            history(entry(40, "GREEN")).hasLapsedReferral,
        )
    }

    @Test
    fun `safety - what the history may and may not assert`() {
        assertEquals(
            "a later GREEN must not erase an earlier RED",
            "RED",
            history(entry(90, "RED"), entry(10, "GREEN")).worstTier,
        )
        assertEquals(
            "a confirmed patient is never treated as routine",
            "under_clinician_care",
            history(entry(90, "RED", outcome = ClinicianOutcome.CONFIRMED), entry(2, "GREEN")).repeatReasonKey,
        )
    }

    @Test
    fun `feature 3 - which records can train the model`() {
        val labelled = listOf(
            ClinicianOutcome.CONFIRMED, ClinicianOutcome.NOT_CONFIRMED,
            ClinicianOutcome.INCONCLUSIVE, null,
        )
        val usable = labelled.count { it?.isTrainingLabel == true }
        assertEquals(
            "inconclusive is NOT a negative - counting it as one would teach the " +
                "model that intermittent AF is absence of AF",
            2,
            usable,
        )
    }
}
