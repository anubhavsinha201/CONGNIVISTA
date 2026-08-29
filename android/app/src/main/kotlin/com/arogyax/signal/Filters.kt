package com.arogyax.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR section, direct-form II transposed.
 *
 * Coefficients follow the RBJ audio-EQ cookbook, normalised so a0 == 1. Every
 * filter in this port is built from cascaded biquads rather than a high-order
 * polynomial because biquads stay numerically well-conditioned at the very
 * low corner frequencies an ECG highpass needs (0.5 Hz at 250 Hz fs is a pole
 * extremely close to the unit circle).
 *
 * Port of app/lib/signal/filters.dart's Biquad - keep the two in sync.
 */
class Biquad private constructor(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    private var s1 = 0.0
    private var s2 = 0.0

    /**
     * Sets the internal state to the steady state for a constant input [x0].
     *
     * Without this, every filtering pass starts from zero state and rings for
     * the first fraction of a second. On a 30 s window that transient is a
     * large spurious deflection at the start, and Pan-Tompkins will happily
     * report it as an R peak - inventing a beat that never happened.
     */
    fun reset(x0: Double) {
        val gain = b0 + b1 + b2
        val denom = 1 + a1 + a2
        val y0 = if (kotlin.math.abs(denom) < 1e-12) 0.0 else x0 * gain / denom
        s2 = b2 * x0 - a2 * y0
        s1 = b1 * x0 - a1 * y0 + s2
    }

    fun process(x: Double): Double {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    companion object {
        private val sqrt1_2 = 1.0 / sqrt(2.0)

        fun lowPass(fs: Double, f0: Double, q: Double = sqrt1_2): Biquad {
            val w0 = 2 * PI * f0 / fs
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            return Biquad(
                (1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0,
                -2 * cw / a0, (1 - alpha) / a0,
            )
        }

        fun highPass(fs: Double, f0: Double, q: Double = sqrt1_2): Biquad {
            val w0 = 2 * PI * f0 / fs
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            return Biquad(
                (1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0,
                -2 * cw / a0, (1 - alpha) / a0,
            )
        }

        /**
         * Band-stop at [f0]. Use a high [q] (~30) for mains hum so the notch is
         * narrow enough not to eat QRS energy either side of it.
         */
        fun notch(fs: Double, f0: Double, q: Double = 30.0): Biquad {
            val w0 = 2 * PI * f0 / fs
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha
            return Biquad(
                1 / a0, -2 * cw / a0, 1 / a0,
                -2 * cw / a0, (1 - alpha) / a0,
            )
        }
    }
}

/**
 * A cascade of [Biquad] sections applied as one filter.
 *
 * Port of app/lib/signal/filters.dart's FilterChain - keep the two in sync.
 */
class FilterChain(private val sections: List<Biquad>) {

    private fun resetAll(x0: Double) {
        for (s in sections) s.reset(x0)
    }

    private fun forward(x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return x
        val out = x.copyOf()
        for (s in sections) {
            s.reset(out[0])
            for (i in out.indices) out[i] = s.process(out[i])
        }
        return out
    }

    /**
     * Zero-phase filtering: forward pass, reverse, forward pass, reverse.
     *
     * We can afford the non-causal version because we analyse a complete
     * captured window, not a live stream. It matters: a causal filter shifts
     * R peaks in time by its group delay, and since the entire AF signal is
     * the *timing* between R peaks, any frequency-dependent delay is a
     * systematic error in exactly the quantity we care about.
     */
    fun filtfilt(x: DoubleArray): DoubleArray {
        if (x.size < 4) return x.copyOf()

        // Odd-reflection padding, mirroring scipy.signal.filtfilt, so the
        // filter does not see an artificial step at either end of the window.
        val padLen = min(x.size - 1, 750) // 3 s at 250 Hz
        val n = x.size
        val padded = DoubleArray(n + 2 * padLen)
        for (i in 0 until padLen) {
            padded[i] = 2 * x[0] - x[padLen - i]
            padded[padLen + n + i] = 2 * x[n - 1] - x[n - 2 - i]
        }
        System.arraycopy(x, 0, padded, padLen, n)

        var y = forward(padded)
        y = y.reversedArray()
        y = forward(y)
        y = y.reversedArray()

        return y.copyOfRange(padLen, padLen + n)
    }

    /**
     * Causal, stateful filtering for live display. Cheaper, and phase
     * distortion is irrelevant when the output is only being drawn on screen.
     */
    fun filterStreaming(x: DoubleArray, resetState: Boolean = false): DoubleArray {
        if (resetState && x.isNotEmpty()) resetAll(x[0])
        val out = x.copyOf()
        for (s in sections) {
            for (i in out.indices) out[i] = s.process(out[i])
        }
        return out
    }

    companion object {
        /**
         * Standard ECG conditioning: 0.5 Hz highpass (baseline wander),
         * 40 Hz lowpass (EMG and high-frequency noise), 50 Hz notch (Indian mains).
         */
        fun ecgConditioning(fs: Double) = FilterChain(
            listOf(
                Biquad.highPass(fs, 0.5),
                Biquad.lowPass(fs, 40.0),
                Biquad.notch(fs, 50.0),
            ),
        )

        /** Pan-Tompkins QRS band: 5-15 Hz, where QRS energy dominates P and T waves. */
        fun qrsBand(fs: Double) = FilterChain(
            listOf(
                Biquad.highPass(fs, 5.0),
                Biquad.lowPass(fs, 15.0),
            ),
        )

        /**
         * Pulsatile band for contact PPG: 0.5-5 Hz.
         *
         * The lower corner strips the DC and the slow drift from finger pressure
         * and venous pooling; the upper keeps the systolic upstroke sharp enough
         * to time accurately while rejecting sensor noise. Perfusion index must
         * be computed BEFORE this runs - it is a ratio of the pulsatile
         * amplitude to the DC level, and this chain removes the DC.
         */
        fun ppgBand(fs: Double) = FilterChain(
            listOf(
                Biquad.highPass(fs, 0.5),
                Biquad.lowPass(fs, 5.0),
            ),
        )
    }
}
