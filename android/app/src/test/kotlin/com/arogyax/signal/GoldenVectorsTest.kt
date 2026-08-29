package com.arogyax.signal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks this Kotlin port against the exact same golden vectors
 * app/test/fixtures/golden_vectors.json was written for - the file
 * app/test/policy_test.dart and friends were meant to assert against before
 * ticket 019 moved the app target to Kotlin. Same fixtures, same tolerance
 * (1e-6, per CLAUDE.md), new language.
 *
 * If this fails, either the Kotlin port has a bug or a DSP constant drifted
 * out of sync with the Dart sources under app/lib/signal and
 * ml/reference/dsp_reference.py - regenerate the fixture via
 * `python ml/reference/validate_dsp.py` and check which side is actually
 * right before touching this test.
 */
class GoldenVectorsTest {
    private val fixture: JSONObject by lazy {
        // Gradle unit tests run with the module dir (android/app) as the
        // working directory - repo root is two levels up.
        val file = File("../../app/test/fixtures/golden_vectors.json")
        require(file.exists()) { "golden_vectors.json not found at ${file.absolutePath}" }
        JSONObject(file.readText())
    }

    private val fs: Double by lazy { fixture.getDouble("fs") }

    private fun eachCase(block: (name: String, samples: DoubleArray, case: JSONObject) -> Unit) {
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val samplesJson = case.getJSONArray("samples")
            val samples = DoubleArray(samplesJson.length()) { samplesJson.getDouble(it) }
            block(case.getString("name"), samples, case)
        }
    }

    @Test
    fun `SQI matches the golden vectors`() {
        eachCase { name, samples, case ->
            val expected = case.getJSONObject("expectedSqi")
            val actual = SqiAnalyser(fs).analyse(samples)

            assertEquals("$name: sqi.score", expected.getDouble("score"), actual.score, 1e-6)
            assertEquals(
                "$name: sqi.saturationFraction",
                expected.getDouble("saturationFraction"),
                actual.saturationFraction,
                1e-6,
            )
            assertEquals(
                "$name: sqi.flatlineFraction",
                expected.getDouble("flatlineFraction"),
                actual.flatlineFraction,
                1e-6,
            )
            assertEquals(
                "$name: sqi.powerlineRatio",
                expected.getDouble("powerlineRatio"),
                actual.powerlineRatio,
                1e-6,
            )
            assertEquals(
                "$name: sqi.baselineWanderRatio",
                expected.getDouble("baselineWanderRatio"),
                actual.baselineWanderRatio,
                1e-6,
            )
        }
    }

    @Test
    fun `R-peak detection matches the golden vectors`() {
        eachCase { name, samples, case ->
            val expectedPeaksJson = case.getJSONArray("expectedPeaks")
            val expectedPeaks = List(expectedPeaksJson.length()) { expectedPeaksJson.getInt(it) }

            val actualPeaks = PanTompkins(fs).detect(samples).peaks

            assertEquals("$name: peak count", expectedPeaks.size, actualPeaks.size)
            assertEquals("$name: peak indices", expectedPeaks, actualPeaks)
        }
    }

    @Test
    fun `RR features match the golden vectors`() {
        eachCase { name, samples, case ->
            val result = PanTompkins(fs).detect(samples)
            val rr = RrAnalyser().analyse(result.rrIntervalsMs(fs))
            val expected = case.getJSONObject("expectedRrFeatures")

            assertEquals("$name: rr.count", expected.getInt("count"), rr.count)
            assertEquals("$name: rr.meanRrMs", expected.getDouble("meanRrMs"), rr.meanRrMs, 1e-6)
            assertEquals("$name: rr.meanHr", expected.getDouble("meanHr"), rr.meanHr, 1e-6)
            assertEquals("$name: rr.rmssdMs", expected.getDouble("rmssdMs"), rr.rmssdMs, 1e-6)
            assertEquals(
                "$name: rr.normalisedRmssd",
                expected.getDouble("normalisedRmssd"),
                rr.normalisedRmssd,
                1e-6,
            )
            assertEquals("$name: rr.pnn50", expected.getDouble("pnn50"), rr.pnn50, 1e-6)
            assertEquals(
                "$name: rr.normalisedShannonEntropy",
                expected.getDouble("normalisedShannonEntropy"),
                rr.normalisedShannonEntropy,
                1e-6,
            )
            assertEquals(
                "$name: rr.irregularityScore",
                expected.getDouble("irregularityScore"),
                rr.irregularityScore,
                1e-6,
            )
            assertEquals(
                "$name: rr.rejectedIntervals",
                expected.getInt("rejectedIntervals"),
                rr.rejectedIntervals,
            )
        }
    }

    @Test
    fun `fixture actually has cases - a green suite with zero cases proves nothing`() {
        assertTrue(fixture.getJSONArray("cases").length() > 0)
    }
}
