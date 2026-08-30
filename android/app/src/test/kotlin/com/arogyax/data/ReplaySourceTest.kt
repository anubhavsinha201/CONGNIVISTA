package com.arogyax.data

import com.arogyax.signal.PanTompkins
import com.arogyax.signal.RrAnalyser
import com.arogyax.signal.SqiAnalyser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves two separate things about the bundled replay trace (ticket 009):
 *
 * 1. [ReplaySource] correctly decodes the bundled asset - a mechanical check.
 * 2. The REAL, labelled AF recording it contains is actually recognised as
 *    irregular by the already-verified signal chain (modules 1-2 of
 *    ticket 019) - not a mechanical check, a genuine end-to-end proof that
 *    "demo integrity" (CLAUDE.md) means something: this is not a synthetic
 *    signal engineered to pass, it is a real PhysioNet recording run through
 *    the identical pipeline the app itself uses.
 *
 * Deliberately does not check a final tier - Policy (module 3) isn't ported
 * yet. The gate values below are Policy's own (kSqiGate, kMinRrIntervals,
 * kRrIrregularityGate), checked directly since Policy itself isn't callable yet.
 *
 * Every gate is asserted with MARGIN, not merely cleared. The first trace shipped
 * here (A00004) produced exactly 30 RR intervals against a `>= 30` gate, so one
 * missed R peak would have turned the flagship AF demo into a RETAKE - a green
 * test that was one sample away from meaning nothing. A02501 replaced it and
 * ml/reference/export_replay_trace.py now refuses to emit a trace that clears any
 * gate by less than 25%.
 */
class ReplaySourceTest {
    private val fs = 250.0
    private val assetFile = File(
        "src/main/assets/replay/af_A02501_250hz.raw",
    )

    @Test
    fun `decodes the bundled asset to the expected sample count`() {
        require(assetFile.exists()) { "replay asset not found at ${assetFile.absolutePath}" }
        val source = ReplaySource { assetFile.inputStream() }

        val samples = source.captureEcg()

        // 15000 bytes / 2 bytes per int16 = 7500 samples = 30 s @ 250 Hz.
        assertEquals(7500, samples.size)
    }

    @Test
    fun `the real bundled AF recording is recognised as irregular by our own pipeline`() {
        val source = ReplaySource { assetFile.inputStream() }
        val raw = source.captureEcg()

        val sqi = SqiAnalyser(fs).analyse(raw)
        assertTrue(
            "replay trace should clear the signal-quality gate with margin " +
                "(score=${sqi.score}, gate 0.5, reason=${sqi.failureReason})",
            sqi.score >= MIN_SQI,
        )

        val peaks = PanTompkins(fs).detect(raw)
        val rr = RrAnalyser().analyse(peaks.rrIntervalsMs(fs))

        assertTrue(
            "expected comfortably more than the 30-interval gate, got ${rr.count} - " +
                "a trace that clears this by nothing is one missed R peak from a RETAKE",
            rr.count >= MIN_RR_INTERVALS,
        )
        assertTrue(
            "this is a genuine PhysioNet AF recording (A02501, REFERENCE.csv label 'A') - " +
                "it should score as irregular with margin (score=${rr.irregularityScore}, gate 0.5)",
            rr.irregularityScore >= MIN_IRREGULARITY,
        )
        assertTrue(
            "mean HR ${rr.meanHr} should sit inside the normal band away from both edges, " +
                "so the demo decides on irregularity rather than on rate",
            rr.meanHr in MIN_HR..MAX_HR,
        )
    }

    private companion object {
        // Policy's gates are 30 / 0.5 / 0.5 and a 50-120 normal band. These are the
        // same gates plus the 25% headroom export_replay_trace.py enforces, so the
        // asset and the test cannot drift apart silently.
        const val MIN_RR_INTERVALS = 38      // gate 30, +25%
        const val MIN_SQI = 0.625            // gate 0.5, +25%
        const val MIN_IRREGULARITY = 0.625   // gate 0.5, +25%
        const val MIN_HR = 67.5              // 50 + 25% of the 50-120 band
        const val MAX_HR = 102.5             // 120 - 25% of the 50-120 band
    }
}
