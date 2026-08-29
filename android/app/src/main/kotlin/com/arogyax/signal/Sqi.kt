package com.arogyax.signal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Signal quality assessment for a captured ECG window.
 *
 * Port of app/lib/signal/sqi.dart's SqiResult - keep the two in sync.
 */
data class SqiResult(
    /** Overall 0-1 quality. The tier policy gates on this. */
    val score: Double,
    /** Fraction of samples at or near the ADC rail. */
    val saturationFraction: Double,
    /** Fraction of samples inside a run of no change (disconnected lead, or a stuck ADC). */
    val flatlineFraction: Double,
    /** Power in the 48-52 Hz mains band as a fraction of total signal power. */
    val powerlineRatio: Double,
    /** Power below 0.5 Hz as a fraction of total power - breathing, electrode movement, cable sway. */
    val baselineWanderRatio: Double,
    /** Human-readable reason the window failed, or null if it passed. */
    val failureReason: String?,
)

/**
 * Scores how trustworthy a captured window is, before anything tries to
 * interpret it.
 *
 * This exists because of a specific failure mode described in
 * docs/PRODUCT.md section 5.4: a poor-contact trace that gets scored anyway
 * becomes a false referral, and false referrals are what discredit community
 * screening programmes and get them shut down. Refusing to answer is a
 * product feature, not an error path.
 *
 * Port of app/lib/signal/sqi.dart's SqiAnalyser - keep the two in sync.
 */
class SqiAnalyser(private val fs: Double) {

    fun analyse(raw: DoubleArray): SqiResult {
        if (raw.size < fs.toInt()) {
            return SqiResult(
                score = 0.0,
                saturationFraction = 0.0,
                flatlineFraction = 0.0,
                powerlineRatio = 0.0,
                baselineWanderRatio = 0.0,
                failureReason = "Capture too short to assess",
            )
        }

        val saturation = saturationFraction(raw)
        val flatline = flatlineFraction(raw)

        val totalPower = variance(raw)
        val powerline = if (totalPower > 0) bandPower(raw, 48.0, 52.0) / totalPower else 0.0
        val wander = if (totalPower > 0) lowFrequencyPower(raw) / totalPower else 0.0

        // Multiplicative combination: this is a gate, so any single failure
        // must be able to fail the whole window on its own. Averaging would
        // let a pristine baseline mask a completely detached electrode.
        var score = 1.0
        score *= 1.0 - min(1.0, saturation / K_SATURATION_FAIL)
        score *= 1.0 - min(1.0, flatline / 0.10)
        score *= 1.0 - min(1.0, powerline / 0.50)
        score *= 1.0 - min(1.0, wander / 0.80)
        score = score.coerceIn(0.0, 1.0)

        val reason = when {
            flatline >= 0.10 -> "Electrode contact lost"
            saturation >= K_SATURATION_FAIL -> "Signal clipping - check electrode placement"
            powerline >= 0.50 -> "Mains interference - move away from wiring, unplug the charger"
            wander >= 0.80 -> "Baseline drift - ask the patient to stay still"
            else -> null
        }

        return SqiResult(
            score = score,
            saturationFraction = saturation,
            flatlineFraction = flatline,
            powerlineRatio = powerline,
            baselineWanderRatio = wander,
            failureReason = reason,
        )
    }

    private fun saturationFraction(x: DoubleArray): Double {
        var n = 0
        for (v in x) if (abs(v) >= RAIL_MAGNITUDE) n++
        return n.toDouble() / x.size
    }

    private fun flatlineFraction(x: DoubleArray): Double {
        val minRun = max(2, Math.round(FLATLINE_RUN_SECONDS * fs).toInt())
        var flat = 0
        var runStart = 0
        for (i in 1..x.size) {
            val continues = i < x.size && abs(x[i] - x[i - 1]) < 1e-9
            if (!continues) {
                val runLen = i - runStart
                if (runLen >= minRun) flat += runLen
                runStart = i
            }
        }
        return flat.toDouble() / x.size
    }

    private fun variance(x: DoubleArray): Double {
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var acc = 0.0
        for (v in x) {
            val d = v - mean
            acc += d * d
        }
        return acc / x.size
    }

    /**
     * Power in [loHz, hiHz], summed over DFT bins via the Goertzel algorithm.
     *
     * Goertzel rather than a full FFT because we only ever ask about a
     * handful of narrow bands. It needs no power-of-two length, no buffer
     * allocation, and no FFT dependency in the app.
     */
    private fun bandPower(x: DoubleArray, loHz: Double, hiHz: Double): Double {
        val n = x.size
        val kLo = (loHz * n / fs).toInt()
        val kHi = kotlin.math.ceil(hiHz * n / fs).toInt()
        var total = 0.0
        for (k in max(1, kLo)..min(kHi, n / 2)) {
            total += goertzelPower(x, k)
        }
        // Parseval scaling, so the result is comparable with a time-domain variance.
        return total / (n.toDouble() * n / 2)
    }

    private fun goertzelPower(x: DoubleArray, k: Int): Double {
        val w = 2 * PI * k / x.size
        val coeff = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s0 = v + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    /**
     * Power below 0.5 Hz, obtained as the residual after removing everything
     * above 0.5 Hz with the same zero-phase highpass used for conditioning.
     *
     * An earlier version estimated this with a 2 s moving average, which is
     * wrong in a subtle way: a moving average has a sinc response, so at
     * 0.25 Hz it already passes only ~40% of the power it is trying to
     * measure. See the doc comment on this method in sqi.dart for the full
     * explanation - keep this note in sync with that one.
     */
    private fun lowFrequencyPower(x: DoubleArray): Double {
        val highpassed = FilterChain(listOf(Biquad.highPass(fs, 0.5))).filtfilt(x)
        val low = DoubleArray(x.size) { x[it] - highpassed[it] }
        return variance(low)
    }

    companion object {
        /**
         * Samples arrive as ADC value minus 2048 (see contracts/ble.md), so
         * the rails sit at +/- 2048. Treat anything within 8 counts as clipped.
         */
        const val RAIL_MAGNITUDE = 2040.0

        /** A run of identical samples this long or longer counts as flatline. */
        const val FLATLINE_RUN_SECONDS = 0.05

        /**
         * Saturated-sample fraction at which the window is fully disqualified.
         * Deliberately low - see the doc comment on this constant in sqi.dart
         * for why 5% could never fire on real clipping at all.
         */
        const val K_SATURATION_FAIL = 0.02
    }
}
