package com.arogyax.core

import android.content.Context
import android.media.MediaPlayer

/**
 * Real [ClipPlayer], backed by `android.media.MediaPlayer` reading from the
 * app's bundled assets.
 *
 * **Not unit-tested, and not fakeable into being.** Android's own
 * `android.media.MediaPlayer` is a compile-only stub outside a real device
 * or emulator - calling it in a plain JVM test throws "not mocked", and a
 * Robolectric shadow would be simulating the exact behavior this class
 * exists to get right, proving nothing about whether it actually works.
 * [SequencePlayer]'s branching logic (chaining, empty lists, cancellation)
 * is what's genuinely testable and is tested, in [ClipPlayer]'s own file.
 * This class is the small, honestly-unverified remainder - correct by
 * inspection against the `MediaPlayer` API, confirmed for real once ticket
 * 010's UI exists on an actual device or emulator.
 */
class MediaPlayerClipPlayer(private val context: Context) : ClipPlayer {
    private var current: MediaPlayer? = null

    override fun play(assetPath: String, onComplete: () -> Unit, onError: () -> Unit) {
        stop()
        val mp = MediaPlayer()
        current = mp
        try {
            context.assets.openFd(assetPath).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setOnCompletionListener {
                it.release()
                if (current === it) current = null
                onComplete()
            }
            mp.setOnErrorListener { p, _, _ ->
                p.release()
                if (current === p) current = null
                onError()
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            mp.release()
            current = null
            onError()
        }
    }

    override fun stop() {
        current?.let {
            try {
                it.stop()
            } catch (_: Exception) {
                // Already stopped/released - nothing to do.
            }
            it.release()
        }
        current = null
    }
}
