package com.arogyax.data

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where a capture's raw ECG samples come from - a live sensor unit over BLE,
 * or a bundled recording played back for a demo or a test.
 *
 * This is the abstraction CLAUDE.md calls "the most important abstraction in
 * the app": it is what lets the app run, and be demoed, with no ESP32, no
 * Bluetooth stack, and no patient in the room. Ticket 014's BleSource (not
 * built yet) will be the other implementation of this same interface: the
 * signal chain, and everything above it, must not be able to tell which one
 * it is talking to.
 */
interface SignalSource {
    /**
     * One full capture window: raw ADC samples exactly as contracts/ble.md's
     * ECG frame delivers them - ADC value minus 2048, so a live BLE source
     * and a replay source hand the signal chain the identical domain.
     */
    fun captureEcg(): DoubleArray
}

/**
 * Plays back a bundled recording instead of live hardware.
 *
 * Why this exists at all, per CLAUDE.md's "Demo integrity" section: atrial
 * fibrillation cannot be induced in a healthy teammate on stage. The stage
 * demo instead replays a REAL, labelled AF recording through the identical
 * on-device pipeline - this class is that replay mechanism, not a
 * synthetic-signal stand-in. Whoever runs the demo must say out loud that
 * this is a replay, never present it as a live capture.
 *
 * Depends only on a generic [InputStream] supplier, not on
 * `android.content.res.AssetManager` directly, so it stays testable in a
 * plain JVM unit test with no Android instrumentation - the same reason
 * the signal chain in [com.arogyax.signal] depends on nothing beyond the
 * Kotlin standard library. The real Android-asset-backed supplier is a
 * one-line wrapper, added when ticket 010's UI needs it:
 * `ReplaySource { context.assets.open("replay/af_A00004_250hz.raw") }`.
 */
class ReplaySource(private val open: () -> InputStream) : SignalSource {
    override fun captureEcg(): DoubleArray {
        val bytes = open().use { it.readBytes() }
        require(bytes.size % 2 == 0) { "replay trace byte count must be even (int16 samples)" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bytes.size / 2
        return DoubleArray(n) { buf.getShort(it * 2).toDouble() }
    }
}
