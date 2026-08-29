package com.arogyax.signal

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Statistics over a series of RR intervals, plus the combined irregularity
 * score the tier policy consumes.
 *
 * Port of app/lib/signal/rr_features.dart's RrFeatures - keep the two in sync.
 */
data class RrFeatures(
    val count: Int,
    val meanRrMs: Double,
    val meanHr: Double,
    val rmssdMs: Double,
    /** RMSSD normalised by mean RR. Dimensionless, so it does not drift with heart rate. */
    val normalisedRmssd: Double,
    /** Fraction of successive intervals differing by more than 50 ms. */
    val pnn50: Double,
    /** Shannon entropy of the RR histogram, normalised to 0-1. */
    val normalisedShannonEntropy: Double,
    /** Combined 0-1 irregularity score. Consumed by the tier policy. */
    val irregularityScore: Double,
    /** Intervals discarded as physiologically impossible. */
    val rejectedIntervals: Int,
) {
    companion object {
        val EMPTY = RrFeatures(
            count = 0,
            meanRrMs = 0.0,
            meanHr = 0.0,
            rmssdMs = 0.0,
            normalisedRmssd = 0.0,
            pnn50 = 0.0,
            normalisedShannonEntropy = 0.0,
            irregularityScore = 0.0,
            rejectedIntervals = 0,
        )
    }
}

/**
 * Extracts the RR-interval statistics that discriminate atrial fibrillation.
 *
 * The three measures are the ones repeatedly validated in the AF-screening
 * literature (Dash et al. 2009; Lian et al. 2011): normalised RMSSD, pNN50,
 * and the normalised Shannon entropy of the RR histogram. This is not a
 * fallback for the CNN - irregularly-irregular RR timing is the clinical
 * signature of AF, and unlike a neural network it can be explained, in full,
 * to a clinician who asks why a patient was referred.
 *
 * Port of app/lib/signal/rr_features.dart's RrAnalyser - keep the two in sync,
 * including the fitted constants below (measured against MIT-BIH AFDB, see
 * the Dart doc comment for the full provenance).
 */
class RrAnalyser {
    fun analyse(rrMs: DoubleArray): RrFeatures {
        val clean = mutableListOf<Double>()
        var rejected = 0
        for (rr in rrMs) {
            if (rr in MIN_PLAUSIBLE_RR_MS..MAX_PLAUSIBLE_RR_MS) {
                clean.add(rr)
            } else {
                rejected++
            }
        }

        // NOTE: we filter only physiologically impossible intervals. The
        // usual ectopic-beat filter - drop any interval deviating more than
        // ~20% from the running median - is deliberately NOT applied here.
        // In atrial fibrillation, large deviations from the median are not
        // artefact; they are the finding.

        if (clean.size < 2) return RrFeatures.EMPTY

        val n = clean.size
        val meanRr = clean.sum() / n

        var sumSqDiff = 0.0
        var over50 = 0
        for (i in 1 until n) {
            val d = clean[i] - clean[i - 1]
            sumSqDiff += d * d
            if (kotlin.math.abs(d) > 50) over50++
        }
        val rmssd = sqrt(sumSqDiff / (n - 1))
        val pnn50 = over50.toDouble() / (n - 1)
        val nRmssd = if (meanRr > 0) rmssd / meanRr else 0.0
        val entropy = normalisedShannonEntropy(clean)

        val score = (
            W_RMSSD * logistic(nRmssd, N_RMSSD_CENTRE, N_RMSSD_WIDTH) +
                W_PNN50 * logistic(pnn50, PNN50_CENTRE, PNN50_WIDTH) +
                W_ENTROPY * logistic(entropy, ENTROPY_CENTRE, ENTROPY_WIDTH)
            ).coerceIn(0.0, 1.0)

        return RrFeatures(
            count = n,
            meanRrMs = meanRr,
            meanHr = if (meanRr > 0) 60000.0 / meanRr else 0.0,
            rmssdMs = rmssd,
            normalisedRmssd = nRmssd,
            pnn50 = pnn50,
            normalisedShannonEntropy = entropy,
            irregularityScore = score,
            rejectedIntervals = rejected,
        )
    }

    /**
     * Shannon entropy of a 16-bin RR histogram, normalised by ln(16) so the
     * result is 0 (all intervals identical) to 1 (uniformly spread).
     *
     * The histogram range is taken after trimming the most extreme 5% at
     * each end, so a single outlier cannot stretch the bin width and
     * artificially collapse the distribution into one bin.
     */
    private fun normalisedShannonEntropy(rr: List<Double>): Double {
        val sorted = rr.sorted()
        val trim = (sorted.size * 0.05).toInt()
        val lo = sorted[trim]
        val hi = sorted[sorted.size - 1 - trim]
        val range = hi - lo
        if (range <= 0) return 0.0

        val counts = IntArray(HISTOGRAM_BINS)
        var total = 0
        for (v in rr) {
            if (v < lo || v > hi) continue
            var b = ((v - lo) / range * HISTOGRAM_BINS).toInt()
            if (b >= HISTOGRAM_BINS) b = HISTOGRAM_BINS - 1
            counts[b]++
            total++
        }
        if (total == 0) return 0.0

        var h = 0.0
        for (c in counts) {
            if (c == 0) continue
            val p = c.toDouble() / total
            h -= p * ln(p)
        }
        return (h / ln(HISTOGRAM_BINS.toDouble())).coerceIn(0.0, 1.0)
    }

    /**
     * Soft threshold. A hard cut-off makes the score jump between two
     * patients whose intervals differ by a millisecond; a logistic keeps the
     * output continuous, which is also what lets it be compared against the
     * CNN score on the same 0-1 scale.
     */
    private fun logistic(x: Double, centre: Double, width: Double): Double =
        1.0 / (1.0 + exp(-(x - centre) / width))

    companion object {
        /** Physiological plausibility bounds: 30-200 bpm. */
        const val MIN_PLAUSIBLE_RR_MS = 300.0
        const val MAX_PLAUSIBLE_RR_MS = 2000.0

        const val HISTOGRAM_BINS = 16

        // MEASURED 2026-08-30 against MIT-BIH AFDB - see the doc comment on
        // RrAnalyser in app/lib/signal/rr_features.dart for the full fitting
        // methodology and cross-validation numbers (Se 0.957, Sp 0.911).
        const val N_RMSSD_CENTRE = 0.1938
        const val N_RMSSD_WIDTH = 0.0565
        const val PNN50_CENTRE = 0.4775
        const val PNN50_WIDTH = 0.1023
        const val ENTROPY_CENTRE = 0.8373
        const val ENTROPY_WIDTH = 0.0508

        const val W_RMSSD = 0.4
        const val W_PNN50 = 0.3
        const val W_ENTROPY = 0.3
    }
}
