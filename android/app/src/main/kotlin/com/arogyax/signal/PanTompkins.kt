package com.arogyax.signal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RPeakResult(
    /** Sample indices of detected R peaks, ascending. */
    val peaks: List<Int>,
    /** The 5-15 Hz zero-phase bandpassed signal the peaks were refined against. */
    val qrsBand: DoubleArray,
    /** Moving-window-integrated energy envelope, for debugging and overlay. */
    val integrated: DoubleArray,
) {
    /** RR intervals in milliseconds. */
    fun rrIntervalsMs(fs: Double): DoubleArray {
        if (peaks.size < 2) return DoubleArray(0)
        val rr = DoubleArray(peaks.size - 1)
        for (i in rr.indices) {
            rr[i] = (peaks[i + 1] - peaks[i]) * 1000.0 / fs
        }
        return rr
    }
}

/**
 * Pan-Tompkins QRS detection, adapted for offline analysis of a captured window.
 *
 * Two deliberate deviations from the 1985 paper:
 *
 *  - The bandpass and the moving-window integrator are zero-phase
 *    (forward-backward, and centred). The original is causal because it ran
 *    on 1985 hardware in real time. We analyse a complete 30 s window, so we
 *    can remove the group delay entirely instead of compensating for it.
 *    That matters more here than in a general-purpose monitor: RR interval
 *    timing IS the AF signal, so a systematic time shift is error injected
 *    straight into the quantity being measured.
 *
 *  - Peak locations are refined against the bandpassed signal rather than
 *    read off the integrator, which only resolves QRS position to roughly
 *    its own window width.
 *
 * Port of app/lib/signal/pan_tompkins.dart - keep the two in sync.
 */
class PanTompkins(private val fs: Double) {

    private val mwiWindow: Int = max(1, Math.round(0.150 * fs).toInt()) // 150 ms integration window
    private val refractory: Int = Math.round(0.200 * fs).toInt() // 200 ms physiological blanking
    private val tWaveWindow: Int = Math.round(0.360 * fs).toInt() // 360 ms T-wave discrimination limit
    private val refineWindow: Int = max(1, Math.round(0.060 * fs).toInt()) // +/- 60 ms peak refinement search

    fun detect(raw: DoubleArray): RPeakResult {
        if (raw.size < fs.toInt()) {
            return RPeakResult(emptyList(), DoubleArray(0), DoubleArray(0))
        }

        val band = FilterChain.qrsBand(fs).filtfilt(raw)
        val deriv = derivative(band)
        val squared = DoubleArray(deriv.size) { deriv[it] * deriv[it] }
        val integrated = centredMovingAverage(squared, mwiWindow)

        val candidates = localMaxima(integrated, refractory)
        val accepted = adaptiveThreshold(integrated, deriv, candidates)
        val refined = refineToRPeaks(band, accepted)

        return RPeakResult(refined, band, integrated)
    }

    /**
     * Five-point derivative from the original paper: emphasises the steep QRS
     * slope while suppressing the slower P and T waves.
     */
    private fun derivative(x: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in 4 until x.size) {
            out[i] = (2 * x[i] + x[i - 1] - x[i - 3] - 2 * x[i - 4]) / 8.0
        }
        return out
    }

    /**
     * Centred (zero-phase) moving average, computed with a running sum so the
     * cost is O(n) rather than O(n * window).
     */
    private fun centredMovingAverage(x: DoubleArray, window: Int): DoubleArray {
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        val half = window / 2
        var sum = 0.0
        var lo = -half
        var hi = lo + window - 1
        for (i in max(0, lo)..min(hi, x.size - 1)) sum += x[i]
        for (i in x.indices) {
            if (i > 0) {
                lo = i - half
                hi = lo + window - 1
                if (hi < x.size) sum += x[hi]
                if (lo - 1 >= 0) sum -= x[lo - 1]
            }
            val count = min(hi, x.size - 1) - max(lo, 0) + 1
            out[i] = if (count > 0) sum / count else 0.0
        }
        return out
    }

    /** Local maxima separated by at least [minDistance] samples. */
    private fun localMaxima(x: DoubleArray, minDistance: Int): List<Int> {
        val peaks = mutableListOf<Int>()
        for (i in 1 until x.size - 1) {
            if (x[i] > x[i - 1] && x[i] >= x[i + 1]) {
                if (peaks.isNotEmpty() && i - peaks.last() < minDistance) {
                    // Keep only the taller of two peaks inside the blanking period.
                    if (x[i] > x[peaks.last()]) peaks[peaks.size - 1] = i
                } else {
                    peaks.add(i)
                }
            }
        }
        return peaks
    }

    /** The adaptive dual-threshold rule, with T-wave rejection and searchback. */
    private fun adaptiveThreshold(
        integrated: DoubleArray,
        deriv: DoubleArray,
        candidates: List<Int>,
    ): List<Int> {
        if (candidates.isEmpty()) return emptyList()

        // Initialise from the first two seconds of signal, as the paper specifies.
        val learnEnd = min(integrated.size, (2 * fs).toInt())
        var maxLearn = 0.0
        var sumLearn = 0.0
        for (i in 0 until learnEnd) {
            if (integrated[i] > maxLearn) maxLearn = integrated[i]
            sumLearn += integrated[i]
        }
        var spki = maxLearn / 3.0
        var npki = (sumLearn / max(1, learnEnd)) / 2.0
        var t1 = npki + 0.25 * (spki - npki)

        val qrs = mutableListOf<Int>()
        val rrRecent = mutableListOf<Double>()
        var rrAverage = 0.0

        fun acceptPeak(idx: Int, amp: Double, viaSearchback: Boolean) {
            if (qrs.isNotEmpty()) {
                rrRecent.add((idx - qrs.last()).toDouble())
                if (rrRecent.size > 8) rrRecent.removeAt(0)
                rrAverage = rrRecent.sum() / rrRecent.size
            }
            qrs.add(idx)
            // A searchback detection is weaker evidence, so it updates the
            // running signal-peak estimate at a quarter weight, per the paper.
            spki = if (viaSearchback) 0.25 * amp + 0.75 * spki else 0.125 * amp + 0.875 * spki
        }

        fun maxSlope(centre: Int): Double {
            val lo = max(0, centre - refineWindow)
            val hi = min(deriv.size - 1, centre + refineWindow)
            var best = 0.0
            for (i in lo..hi) {
                val a = abs(deriv[i])
                if (a > best) best = a
            }
            return best
        }

        for (ci in candidates.indices) {
            val idx = candidates[ci]
            val amp = integrated[idx]

            // Searchback: an implausibly long gap usually means a beat was
            // missed because it fell between the two thresholds, so
            // re-examine the gap at t2.
            if (qrs.isNotEmpty() && rrAverage > 0 && (idx - qrs.last()) > 1.66 * rrAverage) {
                val t2 = 0.5 * t1
                var bestIdx = -1
                var bestAmp = 0.0
                var k = ci - 1
                while (k >= 0 && candidates[k] > qrs.last()) {
                    val c = candidates[k]
                    if (c - qrs.last() >= refractory && integrated[c] > t2 && integrated[c] > bestAmp) {
                        bestAmp = integrated[c]
                        bestIdx = c
                    }
                    k--
                }
                if (bestIdx >= 0) acceptPeak(bestIdx, bestAmp, viaSearchback = true)
            }

            if (amp > t1) {
                if (qrs.isNotEmpty() && (idx - qrs.last()) < tWaveWindow) {
                    // Too soon to be a new beat. A T wave rises more slowly
                    // than a QRS, so compare maximum slope against the
                    // previous accepted beat.
                    if (maxSlope(idx) < 0.5 * maxSlope(qrs.last())) {
                        npki = 0.125 * amp + 0.875 * npki
                        t1 = npki + 0.25 * (spki - npki)
                        continue
                    }
                }
                if (qrs.isNotEmpty() && (idx - qrs.last()) < refractory) continue
                acceptPeak(idx, amp, viaSearchback = false)
            } else {
                npki = 0.125 * amp + 0.875 * npki
            }
            t1 = npki + 0.25 * (spki - npki)
        }

        qrs.sort()
        return qrs
    }

    /**
     * The integrator resolves a QRS only to about its own window width, so
     * take the true fiducial point as the largest absolute deflection in the
     * bandpassed signal nearby.
     */
    private fun refineToRPeaks(band: DoubleArray, approx: List<Int>): List<Int> {
        val out = mutableListOf<Int>()
        for (idx in approx) {
            val lo = max(0, idx - refineWindow)
            val hi = min(band.size - 1, idx + refineWindow)
            var bestIdx = idx
            var bestVal = -1.0
            for (i in lo..hi) {
                val v = abs(band[i])
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = i
                }
            }
            if (out.isEmpty() || bestIdx > out.last()) out.add(bestIdx)
        }
        return out
    }
}
