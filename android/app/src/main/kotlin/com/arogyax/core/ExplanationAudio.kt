package com.arogyax.core

import org.json.JSONObject

/**
 * Resolves a [Reason] (a `why.*` key plus its substitution values) into an
 * ordered list of bundled audio clips to play back-to-back - never one
 * spliced file. This is the Indian-Railways pattern: a station PA doesn't
 * synthesize "train number 12621 is arriving" as one recording, it plays
 * "train number" + [12621, pre-recorded as one word] + "is arriving" as
 * separate clips in sequence. Ten of the 33 `why.*` reasons carry a live
 * number (`{hr}`, `{sqi}`...) that's different every screening, so those ten
 * are built the same way: fixed prefix/suffix clips plus a shared 0-200
 * number vocabulary, both generated once by
 * `android/scripts/generate_tamil_segments.py` from
 * `tamil_audio_manifest_DRAFT.json`. The other 23 reasons are a single fixed
 * sentence and resolve to a one-clip list.
 *
 * Pure and testable on purpose, same reasoning as [TierAudioClips] - actually
 * playing the sequence needs `MediaPlayer` and a real device, which is
 * ticket 010's job. This is the part that doesn't need any of that: given a
 * key and its values, which files, in what order.
 *
 * **Every clip this resolves to comes from UNREVIEWED DRAFT text and DRAFT
 * number pronunciations** - see `tamil_audio_manifest_DRAFT.json`'s own
 * header comment. Do not wire this into a build a real worker uses before
 * ticket 011's native-speaker and clinician review lands.
 */
class ExplanationAudio(manifestJson: JSONObject) {
    private val minNumber: Int
    private val maxNumber: Int
    private val segmentIds: Set<String>
    private val keys: JSONObject

    init {
        val range = manifestJson.getJSONObject("number_range")
        minNumber = range.getInt("min")
        maxNumber = range.getInt("max")
        val fixedSegments = manifestJson.getJSONObject("fixed_segments")
        segmentIds = fixedSegments.keys().asSequence().toSet()
        keys = manifestJson.getJSONObject("keys")
    }

    /**
     * Ordered list of asset paths for [key], substituting each `{num:...}`
     * step with the matching entry in [values]. [values] holds
     * display-formatted strings (e.g. `"72%"`, matching [Reason.values]) -
     * only the digits are used for the lookup.
     *
     * A value outside the pre-recorded [minNumber]..[maxNumber] range is
     * clamped to the nearest bound rather than failing the whole sequence -
     * an approximate spoken number is a smaller error than silence for an
     * otherwise-complete sentence. Known, deliberate limitation for the rare
     * out-of-range case, not a claim that clamping is correct.
     */
    fun clipsFor(key: String, values: Map<String, String>): List<String> {
        val steps = keys.optJSONArray(key)
            ?: throw IllegalArgumentException("no audio manifest entry for key: $key")

        val paths = mutableListOf<String>()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            when {
                step.has("seg") -> {
                    val id = step.getString("seg")
                    require(id in segmentIds) { "manifest references unknown segment: $id" }
                    paths.add("audio_DRAFT/segments/$id.mp3")
                }
                step.has("num") -> {
                    val field = step.getString("num")
                    val raw = values[field] ?: throw IllegalArgumentException("no value supplied for {$field} in key: $key")
                    val digits = raw.filter { it.isDigit() }
                    require(digits.isNotEmpty()) { "value for {$field} has no digits: '$raw'" }
                    val n = digits.toInt().coerceIn(minNumber, maxNumber)
                    paths.add("audio_DRAFT/numbers/$n.mp3")
                }
                else -> throw IllegalArgumentException("manifest step is neither seg nor num: $step")
            }
        }
        return paths
    }
}
