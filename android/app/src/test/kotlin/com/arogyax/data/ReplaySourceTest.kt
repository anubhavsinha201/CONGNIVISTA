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
 * yet. `irregularityScore >= 0.5` is the same gate Policy.kRrIrregularityGate
 * uses, checked directly since Policy itself isn't available to call yet.
 */
class ReplaySourceTest {
    private val fs = 250.0
    private val assetFile = File(
        "src/main/assets/replay/af_A00004_250hz.raw",
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
            "replay trace should pass the signal-quality gate (score=${sqi.score}, reason=${sqi.failureReason})",
            sqi.score >= 0.5,
        )

        val peaks = PanTompkins(fs).detect(raw)
        val rr = RrAnalyser().analyse(peaks.rrIntervalsMs(fs))

        assertTrue("expected at least 30 RR intervals, got ${rr.count}", rr.count >= 30)
        assertTrue(
            "this is a genuine PhysioNet AF recording (A00004, REFERENCE.csv label 'A') - " +
                "it should score as irregular (score=${rr.irregularityScore}), same gate Policy uses (0.5)",
            rr.irregularityScore >= 0.5,
        )
    }
}
