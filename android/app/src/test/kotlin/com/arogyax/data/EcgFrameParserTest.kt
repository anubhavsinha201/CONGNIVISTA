package com.arogyax.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgFrameParserTest {

    /** Builds a wire-format ECG frame exactly as the firmware writes it. */
    private fun frame(seq: Int, tMs: Long = 0, samples: IntArray = IntArray(25) { it }): ByteArray {
        require(samples.size == 25)
        val b = ByteArray(56)
        b[0] = (seq and 0xFF).toByte()
        b[1] = ((seq shr 8) and 0xFF).toByte()
        b[2] = (tMs and 0xFF).toByte()
        b[3] = ((tMs shr 8) and 0xFF).toByte()
        b[4] = ((tMs shr 16) and 0xFF).toByte()
        b[5] = ((tMs shr 24) and 0xFF).toByte()
        for (i in 0 until 25) {
            val v = samples[i]
            b[6 + i * 2] = (v and 0xFF).toByte()
            b[7 + i * 2] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    // ---- The safety rule ---------------------------------------------------

    @Test
    fun `a dropped frame invalidates the window`() {
        // The whole reason this class exists. 41, 43 with no 42 deletes 100 ms
        // and fabricates one short RR interval - indistinguishable from AF.
        val p = EcgFrameParser()
        p.onFrame(frame(41))
        assertFalse(p.gapDetected)
        p.onFrame(frame(43))
        assertTrue("a missing frame must invalidate the window", p.gapDetected)
    }

    @Test
    fun `the gap flag latches - a later good run cannot clear it`() {
        val p = EcgFrameParser()
        p.onFrame(frame(1))
        p.onFrame(frame(3))
        repeat(20) { p.onFrame(frame(4 + it)) }
        assertTrue("a clean run after a gap must not clear the flag", p.gapDetected)
    }

    @Test
    fun `a contiguous run reports no gap`() {
        val p = EcgFrameParser()
        for (s in 100..160) p.onFrame(frame(s))
        assertFalse(p.gapDetected)
        assertEquals(61, p.framesReceived)
    }

    @Test
    fun `the uint16 sequence counter wraps without being read as a gap`() {
        // seq wraps every 65536 frames - about 109 minutes of streaming. Calling
        // that a gap throws away a good window; calling a gap a wrap is worse.
        val p = EcgFrameParser()
        p.onFrame(frame(65534))
        p.onFrame(frame(65535))
        p.onFrame(frame(0))
        p.onFrame(frame(1))
        assertFalse("65535 -> 0 is a wrap, not a gap", p.gapDetected)
    }

    @Test
    fun `a wrap that also skips is still a gap`() {
        val p = EcgFrameParser()
        p.onFrame(frame(65535))
        p.onFrame(frame(1)) // 0 is missing
        assertTrue(p.gapDetected)
    }

    @Test
    fun `reset clears the gap so the next capture starts clean`() {
        val p = EcgFrameParser()
        p.onFrame(frame(1)); p.onFrame(frame(5))
        assertTrue(p.gapDetected)
        p.reset()
        assertFalse(p.gapDetected)
        assertEquals(0, p.samplesBuffered)
    }

    // ---- Wire format -------------------------------------------------------

    @Test
    fun `samples are read little-endian`() {
        // The ESP32 is little-endian native. Reading big-endian would turn a
        // 1 count sample into 256 - silently, with no error anywhere.
        val p = EcgFrameParser()
        val out = p.onFrame(frame(0, samples = IntArray(25) { 1 }))
        assertEquals(1.0, out[0], 0.0)
    }

    @Test
    fun `negative samples survive as negative`() {
        // Samples are ADC minus 2048, so roughly half of them are negative.
        // Reading them unsigned puts every trough at ~65000.
        val p = EcgFrameParser()
        val out = p.onFrame(frame(0, samples = IntArray(25) { -2048 }))
        assertEquals(-2048.0, out[0], 0.0)
        assertTrue(out.all { it == -2048.0 })
    }

    @Test
    fun `the full contract range round-trips`() {
        val p = EcgFrameParser()
        val extremes = intArrayOf(-2048, -1, 0, 1, 2047).let { e -> IntArray(25) { e[it % e.size] } }
        val out = p.onFrame(frame(0, samples = extremes))
        assertArrayEquals(extremes.map { it.toDouble() }.toDoubleArray(), out, 0.0)
    }

    @Test
    fun `the 32-bit timestamp is read little-endian and does not go negative`() {
        val p = EcgFrameParser()
        p.onFrame(frame(0, tMs = 4_000_000_000L))
        assertEquals(4_000_000_000L, p.lastTimestampMs)
    }

    // ---- Malformed input ---------------------------------------------------

    @Test
    fun `a wrong-sized payload is dropped rather than parsed past its end`() {
        // Reading off the end of a BLE buffer would fabricate samples out of
        // whatever memory sat next to it.
        val p = EcgFrameParser()
        for (bad in listOf(ByteArray(0), ByteArray(20), ByteArray(55), ByteArray(57), ByteArray(200))) {
            assertEquals(0, p.onFrame(bad).size)
        }
        assertEquals(0, p.framesReceived)
        assertEquals(0, p.samplesBuffered)
    }

    // ---- Window assembly ---------------------------------------------------

    @Test
    fun `a window fills in exactly 300 frames and does not overrun`() {
        val p = EcgFrameParser()
        for (s in 0 until 300) p.onFrame(frame(s))
        assertTrue(p.complete)
        assertEquals(7500, p.samplesBuffered)
        assertEquals(7500, p.window().size)

        // Frames after the window is full must not grow the buffer.
        p.onFrame(frame(300))
        assertEquals(7500, p.samplesBuffered)
        assertEquals(7500, p.window().size)
    }

    @Test
    fun `a short capture returns only what arrived, never zero padding`() {
        // Zero padding would look like a flatline, which SqiAnalyser would read
        // as lost electrode contact - a wrong reason for a right refusal.
        val p = EcgFrameParser()
        for (s in 0 until 10) p.onFrame(frame(s))
        assertFalse(p.complete)
        assertEquals(250, p.window().size)
    }

    @Test
    fun `progress advances monotonically to one`() {
        val p = EcgFrameParser()
        var last = 0.0
        for (s in 0 until 300) {
            p.onFrame(frame(s))
            assertTrue(p.progress >= last)
            last = p.progress
        }
        assertEquals(1.0, p.progress, 1e-9)
    }

    @Test
    fun `samples land in arrival order across frames`() {
        val p = EcgFrameParser()
        p.onFrame(frame(0, samples = IntArray(25) { it }))
        p.onFrame(frame(1, samples = IntArray(25) { 100 + it }))
        val w = p.window()
        assertEquals(0.0, w[0], 0.0)
        assertEquals(24.0, w[24], 0.0)
        assertEquals(100.0, w[25], 0.0)
        assertEquals(124.0, w[49], 0.0)
    }
}

class SensorStatusTest {

    private fun status(flags: Int, battery: Int, seq: Int) = byteArrayOf(
        flags.toByte(), battery.toByte(), (seq and 0xFF).toByte(), ((seq shr 8) and 0xFF).toByte(),
    )

    @Test
    fun `lead-off bits are read from the hardware's own pins`() {
        assertTrue(SensorStatus.parse(status(0x01, 90, 0))!!.leadOffPositive)
        assertTrue(SensorStatus.parse(status(0x02, 90, 0))!!.leadOffNegative)
        assertTrue(SensorStatus.parse(status(0x03, 90, 0))!!.leadOff)
        assertFalse(SensorStatus.parse(status(0x00, 90, 0))!!.leadOff)
    }

    @Test
    fun `either electrode off is enough to call lead-off`() {
        assertTrue(SensorStatus.parse(status(0x01, 90, 0))!!.leadOff)
        assertTrue(SensorStatus.parse(status(0x02, 90, 0))!!.leadOff)
    }

    @Test
    fun `bit3 is the streaming flag`() {
        assertTrue(SensorStatus.parse(status(0x08, 90, 0))!!.streaming)
        assertFalse(SensorStatus.parse(status(0x00, 90, 0))!!.streaming)
    }

    @Test
    fun `battery 255 means unknown, not 255 percent`() {
        assertEquals(null, SensorStatus.parse(status(0, 255, 0))!!.batteryPercent)
        assertEquals(0, SensorStatus.parse(status(0, 0, 0))!!.batteryPercent)
        assertEquals(100, SensorStatus.parse(status(0, 100, 0))!!.batteryPercent)
    }

    @Test
    fun `lastEcgSeq is little-endian`() {
        assertEquals(0x1234, SensorStatus.parse(status(0, 90, 0x1234))!!.lastEcgSeq)
        assertEquals(65535, SensorStatus.parse(status(0, 90, 65535))!!.lastEcgSeq)
    }

    @Test
    fun `a wrong-sized status frame is rejected`() {
        assertEquals(null, SensorStatus.parse(ByteArray(3)))
        assertEquals(null, SensorStatus.parse(ByteArray(5)))
    }
}
