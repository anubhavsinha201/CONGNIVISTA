package com.arogyax.signal

import kotlin.math.max
import kotlin.math.min

/**
 * Quantities that require ECG and PPG captured together on a shared clock.
 *
 * These are the reason the second sensor exists. Everything else the PPG
 * provides is a correlated second opinion on a rhythm the ECG already sees;
 * these are things neither sensor can produce alone. See contracts/ppg.md §6.
 *
 * Port of app/lib/signal/fusion.dart's FusionFeatures - keep the two in sync.
 */
data class FusionFeatures(
    /**
     * HR from the ECG minus pulse rate from the PPG, over the same window.
     *
     * In atrial fibrillation a beat arriving too soon leaves the ventricle
     * underfilled, so its stroke volume is too small to reach the finger.
     * The heart contracts and the periphery feels nothing, making the
     * electrical rate exceed the mechanical one. This is the classic pulse
     * deficit.
     */
    val pulseDeficitBpm: Double,
    /**
     * Fraction of R peaks followed by a pulse within the transit-time window.
     * Finer-grained than the rate difference: it identifies *which* beats
     * failed to perfuse rather than only how many.
     */
    val perfusedBeatFraction: Double,
    /** R peaks that had no matching pulse. */
    val nonPerfusingBeats: Int,
    /** Median pulse transit time (ms) across matched beats. Reported for the record. */
    val medianPttMs: Double,
    /**
     * False when the two captures were not simultaneous, or either side was
     * unusable. When false every field above is meaningless and must be ignored.
     */
    val valid: Boolean,
    /**
     * Set when the deficit came out negative, which is physiologically
     * impossible and indicates the R-peak detector, not the patient.
     */
    val implausible: Boolean = false,
    val invalidReason: String? = null,
) {
    companion object {
        val NONE = FusionFeatures(
            pulseDeficitBpm = 0.0,
            perfusedBeatFraction = 0.0,
            nonPerfusingBeats = 0,
            medianPttMs = 0.0,
            valid = false,
            invalidReason = "ECG and PPG not captured together",
        )
    }
}

/**
 * Port of app/lib/signal/fusion.dart's FusionAnalyser - keep the two in sync.
 */
object FusionAnalyser {
    /**
     * Pulse transit time window, measured **R peak to PPG systolic peak** -
     * not to the pulse foot. The foot arrives ~150-300 ms after the R peak
     * and the systolic peak a further ~140 ms later, so matching against
     * peaks needs the wider bound. Getting this wrong does not throw: it
     * silently reports beats as non-perfusing, which reads as a clinical
     * finding rather than a bug.
     */
    const val K_PTT_MIN_MS = 150.0
    const val K_PTT_MAX_MS = 450.0

    /** The two captures must overlap by at least this long for a rate comparison to mean anything. */
    const val K_MIN_OVERLAP_SEC = 10.0

    /**
     * [ecgPeakTimesMs] and [ppgPeakTimesMs] must both be on the ESP32's
     * single `millis()` timebase (contracts/ppg.md §4). Passing phone arrival
     * times here would be a silent disaster: BLE buffering jitter is tens of
     * milliseconds, the same order as the transit time being measured, so
     * every output would be noise dressed as a clinical finding.
     */
    fun analyse(
        ecgPeakTimesMs: List<Double>,
        ppgPeakTimesMs: List<Double>,
        ppgUsable: Boolean,
        simultaneous: Boolean,
    ): FusionFeatures {
        if (!simultaneous) return FusionFeatures.NONE
        if (!ppgUsable) {
            return FusionFeatures(
                pulseDeficitBpm = 0.0,
                perfusedBeatFraction = 0.0,
                nonPerfusingBeats = 0,
                medianPttMs = 0.0,
                valid = false,
                invalidReason = "PPG below the perfusion gate",
            )
        }
        if (ecgPeakTimesMs.size < 2 || ppgPeakTimesMs.size < 2) {
            return FusionFeatures(
                pulseDeficitBpm = 0.0,
                perfusedBeatFraction = 0.0,
                nonPerfusingBeats = 0,
                medianPttMs = 0.0,
                valid = false,
                invalidReason = "Too few beats to compare",
            )
        }

        // Work on the ECG timeline, bounded to the beats whose pulse could
        // actually have been observed. See the Dart doc comment on this
        // method for why a naive [max(first), min(last)] overlap window is
        // wrong (it silently manufactures a negative deficit).
        val start = max(ecgPeakTimesMs.first(), ppgPeakTimesMs.first() - K_PTT_MAX_MS)
        val end = min(ecgPeakTimesMs.last(), ppgPeakTimesMs.last() - K_PTT_MIN_MS)
        val overlapSec = (end - start) / 1000.0
        if (overlapSec < K_MIN_OVERLAP_SEC) {
            return FusionFeatures(
                pulseDeficitBpm = 0.0,
                perfusedBeatFraction = 0.0,
                nonPerfusingBeats = 0,
                medianPttMs = 0.0,
                valid = false,
                invalidReason = "Captures overlap by only ${"%.1f".format(overlapSec)} s",
            )
        }

        val ecgIn = ecgPeakTimesMs.filter { it in start..end }
        val ppgIn = ppgPeakTimesMs.filter { it in (start + K_PTT_MIN_MS)..(end + K_PTT_MAX_MS) }
        if (ecgIn.size < 2) return FusionFeatures.NONE

        // Match each R peak to the first pulse inside the transit window.
        // Greedy and forward-only: a pulse belongs to at most one beat, and
        // pulses cannot arrive out of order.
        var matched = 0
        var ppgIdx = 0
        val ptts = mutableListOf<Double>()
        for (r in ecgIn) {
            while (ppgIdx < ppgIn.size && ppgIn[ppgIdx] < r + K_PTT_MIN_MS) {
                ppgIdx++
            }
            if (ppgIdx < ppgIn.size && ppgIn[ppgIdx] <= r + K_PTT_MAX_MS) {
                ptts.add(ppgIn[ppgIdx] - r)
                matched++
                ppgIdx++
            }
        }

        val fraction = matched.toDouble() / ecgIn.size
        ptts.sort()
        val medianPtt = if (ptts.isEmpty()) 0.0 else ptts[ptts.size / 2]

        // Pulse deficit derived from the per-beat matching rather than from a
        // difference of two independently-windowed rates - see the Dart doc
        // comment for why this is deliberately not an independent second
        // measurement.
        val hrEcg = (ecgIn.size - 1) / overlapSec * 60.0
        val deficit = hrEcg * (1.0 - fraction)

        // The genuine detector-failure signal: more pulses observed than
        // beats. A finger cannot pulse without a heartbeat.
        val implausible = ppgIn.size > ecgIn.size * 1.15

        return FusionFeatures(
            pulseDeficitBpm = deficit,
            perfusedBeatFraction = fraction,
            nonPerfusingBeats = ecgIn.size - matched,
            medianPttMs = medianPtt,
            valid = !implausible,
            implausible = implausible,
            invalidReason = if (implausible) "More pulses than heartbeats - R-peak detection is wrong" else null,
        )
    }
}
