package com.arogyax.signal

import kotlin.math.max
import kotlin.math.min

/**
 * Result of analysing one contact-PPG capture (MAX30102, IR channel).
 *
 * Port of app/lib/signal/ppg.dart's PpgResult - keep the two in sync.
 */
data class PpgResult(
    /** Sample indices of detected systolic peaks. */
    val peaks: List<Int>,
    /** Inter-beat intervals in milliseconds. */
    val ibiMs: DoubleArray,
    /** Pulse rate from the PPG, beats per minute. */
    val meanPulseRate: Double,
    /** Irregularity of the pulse interval series, on the same 0-1 scale as the ECG's RR irregularity. */
    val irregularityScore: Double,
    /** AC/DC x 100. Below [PpgAnalyser.K_PERFUSION_GATE] the trace is untrustworthy. */
    val perfusionIndex: Double,
    /**
     * Normalised spread of perfusion index across 1 s sub-windows of the
     * capture: (max - min) / mean. Zero for a steady contact, high when the
     * pulsatile signal swings up and down within one capture - the PPG's
     * contribution to inferred motion detection, now that the MPU-6050 is no
     * longer in the BOM. See contracts/ppg.md.
     */
    val perfusionStabilityRatio: Double = 0.0,
    /** Whether this capture may be used for anything at all. */
    val usable: Boolean,
    /** Full interval statistics, for the record and for debugging. */
    val features: RrFeatures,
    val failureReason: String? = null,
) {
    /**
     * Coarse outcome for the pre-screen step and for the screening record.
     * Matches the `ppgResult` enum in contracts/record.schema.json.
     */
    val prescreenOutcome: String
        get() {
            if (!usable) return "unclear"
            if (features.count < PpgAnalyser.K_MIN_BEATS) return "unclear"
            return if (irregularityScore >= PpgAnalyser.K_IRREGULARITY_GATE) "irregular" else "regular"
        }

    companion object {
        val UNUSABLE = PpgResult(
            peaks = emptyList(),
            ibiMs = DoubleArray(0),
            meanPulseRate = 0.0,
            irregularityScore = 0.0,
            perfusionIndex = 0.0,
            usable = false,
            features = RrFeatures.EMPTY,
            failureReason = "No usable pulse signal",
        )
    }
}

/**
 * Contact PPG analysis for the MAX30102 IR channel.
 *
 * Deliberately mirrors the ECG path - same filter machinery, same interval
 * statistics via [RrAnalyser] - so that pulse irregularity and RR irregularity
 * live on one scale and can be compared without a conversion factor anyone
 * has to remember. See contracts/ppg.md.
 *
 * Port of app/lib/signal/ppg.dart's PpgAnalyser - keep the two in sync.
 */
class PpgAnalyser(private val fs: Double = 100.0) {
    private val intervals = RrAnalyser()

    fun analyse(rawIr: DoubleArray): PpgResult {
        if (rawIr.size < fs * 5) return PpgResult.UNUSABLE

        // Perfusion index first: it is a ratio of pulsatile amplitude to the
        // DC level, so it must be measured before the highpass removes the DC.
        val perfusion = perfusionIndex(rawIr)
        if (perfusion < K_PERFUSION_GATE) {
            return PpgResult(
                peaks = emptyList(),
                ibiMs = DoubleArray(0),
                meanPulseRate = 0.0,
                irregularityScore = 0.0,
                perfusionIndex = perfusion,
                usable = false,
                features = RrFeatures.EMPTY,
                failureReason = "Weak pulse signal - warm the finger, rest it gently on the sensor",
            )
        }

        val band = FilterChain.ppgBand(fs).filtfilt(rawIr)
        val peaks = detectSystolicPeaks(band)

        if (peaks.size < 2) {
            return PpgResult(
                peaks = peaks,
                ibiMs = DoubleArray(0),
                meanPulseRate = 0.0,
                irregularityScore = 0.0,
                perfusionIndex = perfusion,
                usable = false,
                features = RrFeatures.EMPTY,
                failureReason = "Could not find a pulse - reposition the finger",
            )
        }

        val ibi = DoubleArray(peaks.size - 1) { (peaks[it + 1] - peaks[it]) * 1000.0 / fs }

        // Reuse the ECG interval statistics verbatim. AF is irregular in the
        // pulse for exactly the reason it is irregular in the RR series, so
        // the same RMSSD / pNN50 / entropy combination applies unchanged.
        val feats = intervals.analyse(ibi)

        return PpgResult(
            peaks = peaks,
            ibiMs = ibi,
            meanPulseRate = feats.meanHr,
            irregularityScore = feats.irregularityScore,
            perfusionIndex = perfusion,
            perfusionStabilityRatio = perfusionStability(rawIr),
            usable = feats.count >= 2,
            features = feats,
        )
    }

    /**
     * AC/DC x 100, the standard perfusion index.
     *
     * AC is taken as the 5th-to-95th percentile span of the pulsatile
     * component rather than the raw peak-to-peak, so a single motion spike
     * cannot manufacture a healthy-looking number.
     */
    private fun perfusionIndex(raw: DoubleArray): Double {
        var dc = 0.0
        for (v in raw) dc += v
        dc /= raw.size
        if (kotlin.math.abs(dc) < 1e-9) return 0.0

        val ac = FilterChain.ppgBand(fs).filtfilt(raw)
        val sorted = ac.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.05).toInt()]
        val hi = sorted[(sorted.size * 0.95).toInt()]

        return kotlin.math.abs(hi - lo) / kotlin.math.abs(dc) * 100.0
    }

    /**
     * Spread of local AC amplitude across 1 s sub-windows, normalised by the
     * capture's overall DC level. See the Dart doc comment on this method in
     * ppg.dart for the full explanation of why the whole capture is filtered
     * once rather than re-filtering each short sub-window independently.
     */
    private fun perfusionStability(raw: DoubleArray): Double {
        var dc = 0.0
        for (v in raw) dc += v
        dc /= raw.size
        if (kotlin.math.abs(dc) < 1e-9) return 0.0

        val ac = FilterChain.ppgBand(fs).filtfilt(raw)
        val window = Math.round(K_MOTION_SUB_WINDOW_SEC * fs).toInt()
        val n = raw.size / window
        if (n < 2) return 0.0

        val spreads = DoubleArray(n)
        for (i in 0 until n) {
            // Copy before sorting, matching perfusionIndex above.
            val seg = ac.copyOfRange(i * window, (i + 1) * window).also { it.sort() }
            val lo = seg[(seg.size * 0.05).toInt()]
            val hi = seg[(seg.size * 0.95).toInt()]
            spreads[i] = kotlin.math.abs(hi - lo) / kotlin.math.abs(dc) * 100.0
        }

        var mean = 0.0
        for (s in spreads) mean += s
        mean /= spreads.size
        if (mean < 1e-9) return 0.0

        var maxV = spreads[0]
        var minV = spreads[0]
        for (s in spreads) {
            if (s > maxV) maxV = s
            if (s < minV) minV = s
        }
        return (maxV - minV) / mean
    }

    /**
     * Systolic peak detection on the bandpassed signal.
     *
     * A PPG pulse is a broad, smooth hump rather than the sharp spike of a
     * QRS, so Pan-Tompkins' derivative-and-square emphasis is the wrong tool.
     * An adaptive amplitude threshold with a physiological refractory period
     * suits the waveform shape better and has far fewer ways to go wrong.
     */
    private fun detectSystolicPeaks(x: DoubleArray): List<Int> {
        val refractory = max(1, Math.round(K_MIN_IBI_MS / 1000.0 * fs).toInt())

        // Threshold from the signal's own distribution: robust to LED current
        // and to how hard the patient is pressing, neither of which we control.
        val sorted = x.copyOf().also { it.sort() }
        val median = sorted[sorted.size / 2]
        val upper = sorted[(sorted.size * 0.75).toInt()]
        val threshold = median + 0.5 * (upper - median)

        val guard = Math.round(K_EDGE_GUARD_MS / 1000.0 * fs).toInt()
        val peaks = mutableListOf<Int>()
        for (i in 1 until x.size - 1) {
            if (i < guard || i >= x.size - guard) continue
            if (x[i] <= threshold) continue
            if (x[i] <= x[i - 1] || x[i] < x[i + 1]) continue
            if (peaks.isNotEmpty() && i - peaks.last() < refractory) {
                if (x[i] > x[peaks.last()]) peaks[peaks.size - 1] = i
                continue
            }
            peaks.add(i)
        }
        return peaks
    }

    companion object {
        /**
         * Perfusion index (%) below which the PPG is discarded. A cold
         * finger, weak contact, or insufficient LED current all land here.
         * Discarding is correct: the ECG decision stands on its own, and a
         * bad PPG must never weaken it.
         */
        const val K_PERFUSION_GATE = 0.3

        /**
         * Minimum systolic peaks before the interval statistics mean
         * anything. Lower than the ECG's 30 because the PPG pre-screen runs
         * on a shorter capture.
         */
        const val K_MIN_BEATS = 12

        /** Pulse rates outside this range are rejected as detection failures rather than findings. */
        const val K_MIN_IBI_MS = 250.0 // 240 bpm
        const val K_MAX_IBI_MS = 2000.0 // 30 bpm

        /**
         * Peaks within this distance of either end of the capture are
         * discarded - zero-phase filtering leaves edge transients large
         * enough to look like beats. See ppg.dart's doc comment on why this
         * matters (it is not cosmetic - it prevents a false "beat detection
         * unreliable" retake on an otherwise good capture).
         */
        const val K_EDGE_GUARD_MS = 500.0

        /** Same gate value as the ECG rules, on the same scale, by construction. */
        const val K_IRREGULARITY_GATE = 0.5

        /** Sub-window length for perfusion stability. */
        const val K_MOTION_SUB_WINDOW_SEC = 1.0
    }
}
