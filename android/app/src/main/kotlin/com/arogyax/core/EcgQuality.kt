package com.arogyax.core

import com.arogyax.signal.SqiAnalyser
import com.arogyax.signal.SqiResult
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ECG quality panel from the advanced spec section 12.
 *
 * This computes **nothing new**. [SqiAnalyser] already multiplies four
 * independent penalty terms together to produce its score; this file names those
 * four terms, so the worker sees *which* one failed instead of a bare number.
 * Recomputing quality here with a second formula would create exactly the kind
 * of drift the golden-vector discipline exists to prevent — the panel would
 * disagree with the gate that actually refused the window.
 *
 * The overall percentage is therefore `SqiResult.score`, not an average of the
 * factors below.
 */
enum class QualityBand { GOOD, FAIR, POOR }

/** One named contributor to the score, on the same 0..1 scale as the whole. */
data class QualityFactor(
    val label: String,
    /** 1.0 = this factor cost the window nothing; 0.0 = it failed the window on its own. */
    val score: Double,
    /** What the worker would physically change. Null when the factor is fine. */
    val hint: String?,
) {
    val band: QualityBand get() = bandFor(score)
    val percent: Int get() = (score * 100).roundToInt()
}

data class EcgQualityReport(
    /** Identical to [SqiResult.score] — the value the gate actually used. */
    val overall: Double,
    val factors: List<QualityFactor>,
    /** Non-null when [SqiAnalyser] refused the window outright. */
    val failureReason: String?,
) {
    val percent: Int get() = (overall * 100).roundToInt()
    val band: QualityBand get() = bandFor(overall)

    /** Passes [Policy.K_SQI_GATE]. Below this the window is never scored. */
    val usable: Boolean get() = overall >= Policy.K_SQI_GATE

    /** The factor that cost the most, for a one-line summary. Null when nothing did. */
    val worst: QualityFactor? get() = factors.minByOrNull { it.score }?.takeIf { it.score < 1.0 }
}

private fun bandFor(score: Double): QualityBand = when {
    score >= 0.75 -> QualityBand.GOOD
    score >= Policy.K_SQI_GATE -> QualityBand.FAIR
    else -> QualityBand.POOR
}

object EcgQuality {

    // The denominators SqiAnalyser divides by. Duplicated deliberately? No -
    // K_SATURATION_FAIL is read from SqiAnalyser. The other three are literals
    // inside SqiAnalyser.analyse()'s score expression and are mirrored here with
    // a test asserting the mirror holds, because promoting them to public
    // constants would change SqiAnalyser, which is golden-vector pinned.
    const val FLATLINE_FAIL = 0.10
    const val POWERLINE_FAIL = 0.50
    const val WANDER_FAIL = 0.80

    /** The penalty term SqiAnalyser applies for one factor: `1 - min(1, value/fail)`. */
    fun term(value: Double, fail: Double): Double = 1.0 - min(1.0, value / fail)

    fun of(sqi: SqiResult): EcgQualityReport {
        val contact = term(sqi.flatlineFraction, FLATLINE_FAIL)
        val amplitude = term(sqi.saturationFraction, SqiAnalyser.K_SATURATION_FAIL)
        val noise = term(sqi.powerlineRatio, POWERLINE_FAIL)
        val steadiness = term(sqi.baselineWanderRatio, WANDER_FAIL)

        return EcgQualityReport(
            overall = sqi.score,
            failureReason = sqi.failureReason,
            factors = listOf(
                QualityFactor(
                    "Electrode contact", contact,
                    if (contact < 1.0) "Press the electrodes firmly onto clean, dry skin" else null,
                ),
                QualityFactor(
                    "Signal amplitude", amplitude,
                    if (amplitude < 1.0) "Signal is clipping - reposition the electrodes" else null,
                ),
                QualityFactor(
                    "Electrical noise", noise,
                    if (noise < 1.0) "Move away from wiring and unplug the charger" else null,
                ),
                QualityFactor(
                    "Steadiness", steadiness,
                    if (steadiness < 1.0) "Ask the patient to sit still and breathe normally" else null,
                ),
            ),
        )
    }
}
