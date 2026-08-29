package com.arogyax.core

/**
 * Plays one clip and reports back when it finishes (or fails). Kept as an
 * interface so [SequencePlayer]'s actual sequencing logic - the part with
 * real branches worth getting right (empty list, chaining, stopping
 * mid-sequence) - is testable with a fake, no `android.media.MediaPlayer`,
 * no device, no emulator involved.
 */
interface ClipPlayer {
    /** Starts playing [assetPath]. Calls exactly one of [onComplete]/[onError], exactly once. */
    fun play(assetPath: String, onComplete: () -> Unit, onError: () -> Unit)

    /** Stops whatever is currently playing, if anything, and releases its resources. */
    fun stop()
}

/**
 * Plays an ordered list of clips back-to-back - the Indian-Railways-style
 * playback [TierAudioClips]/[ExplanationAudio] compute the *sequence* for.
 * Neither of those two classes needed a device to be correct; this is the
 * one piece of the audio feature that actually does, which is why it is
 * kept this small and this separate.
 *
 * A clip that fails to play (missing asset, corrupt file, unsupported
 * codec) is skipped, not treated as fatal - continuing on to the next clip
 * loses a word, one clip out of a several-clip sentence is a more useful
 * failure than aborting the entire explanation because one segment was bad.
 */
class SequencePlayer(private val clipPlayer: ClipPlayer) {
    private var cancelled = false

    fun playSequence(assetPaths: List<String>, onComplete: () -> Unit = {}) {
        cancelled = false
        step(assetPaths, 0, onComplete)
    }

    private fun step(paths: List<String>, index: Int, onComplete: () -> Unit) {
        if (cancelled || index >= paths.size) {
            onComplete()
            return
        }
        clipPlayer.play(
            paths[index],
            onComplete = { step(paths, index + 1, onComplete) },
            onError = { step(paths, index + 1, onComplete) },
        )
    }

    /** Stops the sequence after whatever clip is currently playing finishes starting. */
    fun cancel() {
        cancelled = true
        clipPlayer.stop()
    }
}
