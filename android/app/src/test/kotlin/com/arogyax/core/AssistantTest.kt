package com.arogyax.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTest {

    private val ctx = AssistantContext(
        screenedToday = 12,
        referralsPending = 3,
        referralsOverdue = 2,
        lastTier = Tier.ORANGE,
        lastReasons = listOf(Reason("risk_intermittent_pattern")),
        priorVisits = 4,
        lastQualityPercent = 41,
    )

    // ---- The safety boundary (spec 15.4) -----------------------------------

    @Test
    fun `clinical questions are handed to a clinician, never answered`() {
        val asked = listOf(
            "what medicine should I give",
            "should he take aspirin",
            "what is the dosage",
            "can you prescribe something",
            "does she have a heart condition",
            "what treatment does this need",
            "is it serious",
            "please diagnose this patient",
            "should we start a blood thinner",
        )
        for (q in asked) {
            val a = Assistant.answer(q, ctx)
            assertEquals("'$q' was not deferred", Intent.CLINICAL_QUESTION, a.intent)
            assertTrue("'$q' was not marked as deferred", a.deferredToClinician)
        }
    }

    @Test
    fun `a clinical question wins even when it also looks like another intent`() {
        // "why is this high priority, what medicine..." contains both triggers.
        // Routing it to an explanation would answer the medicine half by
        // implication, so the clinical check runs first.
        val a = Assistant.answer("why was this priority - what medicine do we give?", ctx)
        assertEquals(Intent.CLINICAL_QUESTION, a.intent)
        assertTrue(a.deferredToClinician)
    }

    @Test
    fun `no answer ever names a condition - non-negotiable 1`() {
        val banned = listOf("atrial fibrillation", "fibrillation", "arrhythmia", "afib", "a-fib")
        val questions = Assistant.suggestions() + listOf(
            "why was this patient prioritised", "what medicine", "how many today",
            "which referrals are overdue", "what does red mean", "does sync need internet",
            "tell me about the previous visit", "something entirely unrelated",
        )
        for (q in questions) {
            val text = Assistant.answer(q, ctx).text.lowercase()
            for (b in banned) {
                assertFalse("answer to '$q' names a diagnosis: $text", text.contains(b))
            }
        }
    }

    @Test
    fun `an unrecognised question says so instead of guessing`() {
        // "Never invent missing patient information" is only achievable if the
        // fallback is an admission rather than a plausible-sounding answer.
        val a = Assistant.answer("what is the patient's home address", ctx)
        assertEquals(Intent.UNKNOWN, a.intent)
        assertTrue(a.text.contains("fixed set"))
    }

    @Test
    fun `a blank question is unknown, not a crash`() {
        assertEquals(Intent.UNKNOWN, Assistant.classify(""))
        assertEquals(Intent.UNKNOWN, Assistant.classify("   "))
    }

    // ---- Answers use the caller's real numbers -----------------------------

    @Test
    fun `counts come from the context, not from prose`() {
        val a = Assistant.answer("how many screenings today?", ctx)
        assertEquals(Intent.TODAY_COUNT, a.intent)
        assertTrue(a.text.contains("12"))
        assertTrue(a.text.contains("3"))
    }

    @Test
    fun `overdue referrals reports zero honestly`() {
        val none = Assistant.answer("which referrals are overdue?", ctx.copy(referralsOverdue = 0))
        assertTrue(none.text.contains("No referrals are overdue"))
        val some = Assistant.answer("which referrals are overdue?", ctx)
        assertTrue(some.text.contains("2"))
    }

    @Test
    fun `the explanation lists the reasons actually recorded`() {
        val a = Assistant.answer("why was this patient prioritised?", ctx)
        assertEquals(Intent.WHY_PRIORITISED, a.intent)
        assertTrue(a.text.contains("Flagged on some earlier visits"))
        assertTrue(a.text.contains("not a diagnosis"))
    }

    @Test
    fun `with no screening yet it says so rather than inventing one`() {
        val a = Assistant.answer("why was this prioritised?", AssistantContext())
        assertTrue(a.text.contains("No screening has been completed"))
    }

    @Test
    fun `offline is answered correctly - a result never waits for a network`() {
        val a = Assistant.answer("does sync need internet?", ctx)
        assertEquals(Intent.OFFLINE_HELP, a.intent)
        assertTrue(a.text.contains("offline"))
        assertTrue(a.text.startsWith("No."))
    }

    @Test
    fun `quality help names the attempt limit rather than inviting endless retries`() {
        val a = Assistant.answer("what should I do when signal quality is poor?", ctx)
        assertEquals(Intent.POOR_QUALITY_HELP, a.intent)
        assertTrue(a.text.contains("${AdaptiveRepeat.MAX_ATTEMPTS} attempts"))
        assertTrue("should quote the real last-quality figure", a.text.contains("41%"))
    }

    // ---- Every suggested question must actually work -----------------------

    @Test
    fun `every suggestion the UI offers resolves to a real intent`() {
        // A suggestion chip that falls through to UNKNOWN is worse than no chip.
        for (s in Assistant.suggestions()) {
            assertNotEquals("suggestion '$s' is not understood", Intent.UNKNOWN, Assistant.classify(s))
        }
    }

    @Test
    fun `every reason key the risk engine can emit has readable text`() {
        // A missing key renders as the raw key - visible in review, but it must
        // not happen for anything RiskEngine actually produces.
        val keys = listOf(
            "risk_retake_not_scored", "risk_persistent_pattern", "risk_intermittent_pattern",
            "risk_repeated_suspicious", "risk_trajectory_increasing", "risk_referral_lapsed",
            "risk_referral_open", "risk_previously_confirmed", "risk_no_history_factors",
            "risk_baseline_hr_above", "risk_baseline_hr_below",
        )
        for (k in keys) {
            val rendered = Assistant.readable(Reason(k, mapOf("current" to "101", "baseline" to "72")))
            assertNotEquals("no text for reason key $k", k, rendered)
            assertFalse("unsubstituted placeholder in $k: $rendered", rendered.contains("{"))
        }
    }

    @Test
    fun `an unknown reason key renders as itself rather than as a guess`() {
        assertEquals("some_new_key", Assistant.readable(Reason("some_new_key")))
    }

    @Test
    fun `tier wording carries no condition name and every tier has a timeframe`() {
        for (t in Tier.entries) {
            val label = Assistant.labelOf(t)
            val frame = Assistant.timeframeOf(t)
            assertTrue(label.isNotBlank())
            assertTrue(frame.isNotBlank())
            for (b in listOf("fibrillation", "arrhythmia", "afib")) {
                assertFalse(label.lowercase().contains(b))
                assertFalse(frame.lowercase().contains(b))
            }
        }
    }
}
