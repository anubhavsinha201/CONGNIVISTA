package com.arogyax.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins [Policy.decide] to `ml/reference/validate_policy.py` through
 * `app/test/fixtures/policy_golden_vectors.json` (ticket 019, module 3).
 *
 * The fixture covers the ECG-only decision. `validate_policy.decide()` does not
 * model the `fusionImplausible` gate or PPG corroboration, so those two paths
 * are asserted here directly against `contracts/ppg.md` section 7 instead of
 * being smuggled into the fixture — see the generator's docstring.
 */
class PolicyGoldenVectorsTest {

    private val fixture: JSONObject by lazy {
        val file = File("../../app/test/fixtures/policy_golden_vectors.json")
        require(file.exists()) { "policy_golden_vectors.json not found at ${file.absolutePath}" }
        JSONObject(file.readText())
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else getDouble(key)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    @Test
    fun `every golden case decides identically`() {
        val cases = fixture.getJSONArray("cases")
        for (idx in 0 until cases.length()) {
            val case = cases.getJSONObject(idx)
            val name = case.getString("name")
            val i = case.getJSONObject("inputs")
            val want = case.getJSONObject("expected")

            val actual = Policy.decide(
                TierInputs(
                    sqiScore = i.getDouble("sqiScore"),
                    motionRejected = i.getBoolean("motionRejected"),
                    leadOffDetected = i.getBoolean("leadOffDetected"),
                    dataGapDetected = i.getBoolean("dataGapDetected"),
                    rrIntervalCount = i.getInt("rrIntervalCount"),
                    meanHr = i.getDouble("meanHr"),
                    rrIrregularityScore = i.getDouble("rrIrregularityScore"),
                    cnnScore = i.optDoubleOrNull("cnnScore"),
                    sqiFailureHint = i.optStringOrNull("sqiFailureHint"),
                    historyIntermittent = i.getBoolean("historyIntermittent"),
                    historyPersistent = i.getBoolean("historyPersistent"),
                ),
            )

            assertEquals("$name: tier", want.getString("tier"), actual.tier.name)
            assertEquals("$name: decidedBy", want.getString("decidedBy"), actual.decidedBy.name)
            assertEquals(
                "$name: retakeReason",
                want.optStringOrNull("retakeReason"),
                actual.retakeReason?.name,
            )
            assertEquals("$name: retakeHint", want.optStringOrNull("retakeHint"), actual.retakeHint)
            assertEquals(
                "$name: modelVersion",
                want.getString("modelVersion"),
                Policy.versionFor(actual.decidedBy),
            )
        }
    }

    @Test
    fun `the gate constants match the ones the fixture was generated against`() {
        val g = fixture.getJSONObject("gates")
        assertEquals(g.getDouble("kSqiGate"), Policy.K_SQI_GATE, 0.0)
        assertEquals(g.getInt("kMinRrIntervals"), Policy.K_MIN_RR_INTERVALS)
        assertEquals(g.getDouble("kRrIrregularityGate"), Policy.K_RR_IRREGULARITY_GATE, 0.0)
        assertEquals(g.getDouble("kCnnThresholdInt8"), Policy.K_CNN_THRESHOLD_INT8!!, 0.0)
        assertEquals(g.getDouble("kHrLow"), Policy.K_HR_LOW, 0.0)
        assertEquals(g.getDouble("kHrHigh"), Policy.K_HR_HIGH, 0.0)
    }

    @Test
    fun `fixture actually has cases - a green suite with zero cases proves nothing`() {
        assertTrue(fixture.getJSONArray("cases").length() > 0)
    }

    // ---- The two paths the Python reference does not model ------------------

    /** An irregular window with a normal rate, so only the PPG can escalate it. */
    private fun irregularNormalRate(
        deficit: Double? = null,
        perfused: Double? = null,
        implausible: Boolean = false,
    ) = TierInputs(
        sqiScore = 0.9,
        motionRejected = false,
        leadOffDetected = false,
        dataGapDetected = false,
        rrIntervalCount = 40,
        meanHr = 72.0,
        rrIrregularityScore = 0.9,
        pulseDeficitBpm = deficit,
        perfusedBeatFraction = perfused,
        fusionImplausible = implausible,
    )

    @Test
    fun `fusion implausible refuses the window rather than scoring it`() {
        // More pulses than heartbeats is physiologically impossible, so the
        // R-peak detector missed beats - which fabricates long RR intervals,
        // the same corruption a dropped BLE frame causes.
        val d = Policy.decide(irregularNormalRate(implausible = true))
        assertEquals(Tier.RETAKE, d.tier)
        assertEquals(DecidedBy.GATE, d.decidedBy)
        assertEquals(RetakeReason.BEAT_DETECTION_UNRELIABLE, d.retakeReason)
    }

    @Test
    fun `a pulse deficit escalates an irregular normal-rate window to RED`() {
        val d = Policy.decide(irregularNormalRate(deficit = 12.0, perfused = 0.95))
        assertEquals(Tier.RED, d.tier)
        assertEquals(PpgCorroboration.PULSE_DEFICIT, d.ppg)
    }

    @Test
    fun `non-perfusing beats escalate an irregular normal-rate window to RED`() {
        val d = Policy.decide(irregularNormalRate(deficit = 1.0, perfused = 0.80))
        assertEquals(Tier.RED, d.tier)
        assertEquals(PpgCorroboration.NON_PERFUSING_BEATS, d.ppg)
    }

    @Test
    fun `a PPG that agrees corroborates without escalating`() {
        val d = Policy.decide(irregularNormalRate(deficit = 1.0, perfused = 0.99))
        assertEquals(Tier.YELLOW, d.tier)
        assertEquals(PpgCorroboration.AGREED, d.ppg)
    }

    @Test
    fun `PPG never downgrades a tier - non-negotiable 6`() {
        // A perfect PPG on a window the ECG already called RED by rate must
        // leave it RED. The PPG escalates or corroborates; it never reassures.
        val redByRate = irregularNormalRate(deficit = 0.0, perfused = 1.0)
            .copy(meanHr = 150.0)
        assertEquals(Tier.RED, Policy.decide(redByRate).tier)
    }

    @Test
    fun `PPG alone cannot create a referral from a regular rhythm`() {
        // The ECG found no irregularity, so the PPG is never consulted at all -
        // a "bad" PPG must not manufacture a referral the ECG did not support.
        val regular = irregularNormalRate(deficit = 40.0, perfused = 0.10)
            .copy(rrIrregularityScore = 0.1)
        val d = Policy.decide(regular)
        assertEquals(Tier.GREEN, d.tier)
        assertEquals(PpgCorroboration.NONE, d.ppg)
    }

    @Test
    fun `a missing PPG leaves the ECG decision untouched`() {
        val d = Policy.decide(irregularNormalRate())
        assertEquals(Tier.YELLOW, d.tier)
        assertEquals(PpgCorroboration.NONE, d.ppg)
    }

    @Test
    fun `no worker-facing string in a decision names a diagnosis - non-negotiable 1`() {
        val banned = listOf("atrial fibrillation", "arrhythmia", "af")
        val cases = fixture.getJSONArray("cases")
        for (idx in 0 until cases.length()) {
            val hint = cases.getJSONObject(idx)
                .getJSONObject("expected")
                .optStringOrNull("retakeHint") ?: continue
            val words = hint.lowercase().split(Regex("[^a-z]+"))
            for (b in banned) {
                assertNotEquals("retake hint names a diagnosis: $hint", b, words.find { it == b })
            }
            assertTrue(
                "retake hint names a diagnosis: $hint",
                !hint.lowercase().contains("atrial fibrillation") &&
                    !hint.lowercase().contains("arrhythmia"),
            )
        }
    }
}
