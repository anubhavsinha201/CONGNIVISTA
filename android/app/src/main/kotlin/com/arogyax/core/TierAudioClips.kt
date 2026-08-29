package com.arogyax.core

/**
 * Maps a [Tier] to its bundled spoken-Tamil asset path (ticket 015).
 *
 * Pure and testable on purpose - actually playing a clip needs Android's
 * `MediaPlayer` and a real `Context`/audio device, which is ticket 010's job
 * once the capture-result UI exists to call it from. This is the part that
 * doesn't need any of that: which file goes with which tier.
 *
 * **Every asset this points at is generated from UNREVIEWED DRAFT text**
 * (`android/app/src/main/assets/strings/tamil_strings_DRAFT.json`) - do not
 * wire this into a build a real worker uses before ticket 011's native-speaker
 * and clinician review lands.
 */
object TierAudioClips {
    private const val DIR = "audio_DRAFT"

    fun assetPathFor(tier: Tier): String = "$DIR/${tier.name}.mp3"

    /** The "this is a screening, not a diagnosis" line played after every non-RETAKE result. */
    const val SUPPORTING_LINE_ASSET = "$DIR/SUPPORTING_LINE.mp3"
}
