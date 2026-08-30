package com.arogyax.data

/**
 * Parses ECG notification frames from the sensor unit and assembles them into an
 * analysis window. `contracts/ble.md` §3.
 *
 * Deliberately has no Android type in it, so the part that can silently corrupt a
 * result is testable on a plain JVM.
 *
 * ## The rule this class exists to enforce
 *
 * **A dropped frame invalidates the window.** Frames 41 and 43 arriving without
 * 42 means 100 ms of signal is missing. Concatenating across that gap does not
 * lose a beat — it manufactures one short RR interval out of nothing, and a short
 * RR interval among normal ones is exactly what atrial fibrillation looks like.
 * A BLE glitch must never become a referral, so [gapDetected] latches and the
 * window is refused rather than scored (non-negotiable 3).
 *
 * The counter is `uint16` and wraps at 65535, so "next" is computed modulo 65536.
 * Treating a wrap as a gap would throw away a good window every 109 minutes of
 * streaming; treating a gap as a wrap would do the far worse thing.
 */
class EcgFrameParser(
    /** Samples in one analysis window: 30 s at 250 Hz. */
    val windowSamples: Int = 7500,
) {

    private val buffer = DoubleArray(windowSamples)
    private var filled = 0
    private var lastSeq: Int? = null

    /** Latches on the first sequence gap and stays set until [reset]. */
    var gapDetected: Boolean = false
        private set

    /** Frames accepted since the last [reset]. */
    var framesReceived: Int = 0
        private set

    /** Device `millis()` at the first sample of the most recent frame. */
    var lastTimestampMs: Long = 0
        private set

    val samplesBuffered: Int get() = filled
    val complete: Boolean get() = filled >= windowSamples
    val progress: Double get() = filled.toDouble() / windowSamples

    fun reset() {
        filled = 0
        lastSeq = null
        gapDetected = false
        framesReceived = 0
        lastTimestampMs = 0
    }

    /**
     * Feeds one notification payload.
     *
     * @return the samples this frame contributed, for live display. Empty when
     *   the frame was malformed — a short or oversized payload is dropped rather
     *   than parsed past its end, because reading off the end of a BLE buffer
     *   would fabricate samples out of adjacent memory.
     */
    fun onFrame(payload: ByteArray): DoubleArray {
        if (payload.size != FRAME_BYTES) return DoubleArray(0)

        val seq = u16(payload, 0)
        lastTimestampMs = u32(payload, 2)

        val prev = lastSeq
        if (prev != null && seq != (prev + 1) % SEQ_MODULUS) gapDetected = true
        lastSeq = seq
        framesReceived++

        val out = DoubleArray(SAMPLES_PER_FRAME)
        for (i in 0 until SAMPLES_PER_FRAME) {
            // int16, little-endian. The ESP32 is little-endian native and the
            // firmware writes with explicit shifts; nothing here may assume the
            // platform's default byte order.
            val v = i16(payload, HEADER_BYTES + i * 2).toDouble()
            out[i] = v
            if (filled < windowSamples) buffer[filled++] = v
        }
        return out
    }

    /**
     * The assembled window.
     *
     * Returns exactly [windowSamples] when [complete]; otherwise only what has
     * arrived, so a short capture is visibly short rather than zero-padded into
     * something that looks like a flatline.
     */
    fun window(): DoubleArray =
        if (complete) buffer.copyOf() else buffer.copyOf(filled)

    private companion object {
        const val FRAME_BYTES = 56
        const val HEADER_BYTES = 6
        const val SAMPLES_PER_FRAME = 25
        const val SEQ_MODULUS = 65536

        fun u16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or
                ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or
                ((b[at + 3].toLong() and 0xFF) shl 24)

        fun i16(b: ByteArray, at: Int): Short =
            (((b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)).toShort())
    }
}

/** Status frame, `contracts/ble.md` §4 — 4 bytes at 1 Hz. */
data class SensorStatus(
    val leadOffPositive: Boolean,
    val leadOffNegative: Boolean,
    val streaming: Boolean,
    /** 0-100, or null when the unit reports 255 (unknown). */
    val batteryPercent: Int?,
    val lastEcgSeq: Int,
) {
    /**
     * Either electrode off the skin. Read from the AD8232's own LO+/LO- pins —
     * never inferred in software from a flat trace, because the hardware knows
     * first and knows faster (`ble.md` §4).
     */
    val leadOff: Boolean get() = leadOffPositive || leadOffNegative

    companion object {
        const val FRAME_BYTES = 4

        fun parse(b: ByteArray): SensorStatus? {
            if (b.size != FRAME_BYTES) return null
            val flags = b[0].toInt() and 0xFF
            val battery = b[1].toInt() and 0xFF
            return SensorStatus(
                leadOffPositive = (flags and 0x01) != 0,
                leadOffNegative = (flags and 0x02) != 0,
                // bit2 is reserved, not reused: a firmware built against an
                // earlier revision of the contract must fail visibly rather than
                // set a bit this app would silently misread.
                streaming = (flags and 0x08) != 0,
                batteryPercent = if (battery == 255) null else battery,
                lastEcgSeq = (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8),
            )
        }
    }
}
