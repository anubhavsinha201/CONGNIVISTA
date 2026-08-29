package com.arogyax.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplanationAudioTest {
    private val manifestFile = File("src/main/assets/strings/tamil_audio_manifest_DRAFT.json")
    private val audio: ExplanationAudio by lazy { ExplanationAudio(JSONObject(manifestFile.readText())) }

    @Test
    fun `a fixed, non-dynamic key resolves to exactly one clip`() {
        assertEquals(listOf("audio_DRAFT/segments/repeat_routine_full.mp3"), audio.clipsFor("why.repeat.routine", emptyMap()))
    }

    @Test
    fun `a single-number key resolves to prefix, number, suffix in order`() {
        val clips = audio.clipsFor("why.rate.high", mapOf("hr" to "140"))
        assertEquals(
            listOf(
                "audio_DRAFT/segments/rate_high_prefix.mp3",
                "audio_DRAFT/numbers/140.mp3",
                "audio_DRAFT/segments/beats_suffix.mp3",
            ),
            clips,
        )
    }

    @Test
    fun `a percent-formatted value is resolved by its digits only`() {
        val clips = audio.clipsFor("why.rhythm.irregular", mapOf("score" to "81%"))
        assertTrue("audio_DRAFT/numbers/81.mp3" in clips)
    }

    @Test
    fun `the three-number key resolves all three in the drafted order`() {
        val clips = audio.clipsFor(
            "why.history.flagRate",
            mapOf("days" to "45", "total" to "6", "flagged" to "3"),
        )
        assertEquals(
            listOf(
                "audio_DRAFT/numbers/45.mp3",
                "audio_DRAFT/segments/flag_rate_mid1.mp3",
                "audio_DRAFT/numbers/6.mp3",
                "audio_DRAFT/segments/flag_rate_mid2.mp3",
                "audio_DRAFT/numbers/3.mp3",
                "audio_DRAFT/segments/flag_rate_suffix.mp3",
            ),
            clips,
        )
    }

    @Test
    fun `an out-of-range number clamps to the nearest bound instead of failing`() {
        val clips = audio.clipsFor("why.rate.high", mapOf("hr" to "999"))
        assertTrue("audio_DRAFT/numbers/200.mp3" in clips)
    }

    @Test
    fun `every key in EXPLANATION_KEYS has a manifest entry`() {
        for (key in EXPLANATION_KEYS) {
            // Throws if missing - the assertion is that this loop completes.
            audio.clipsFor(key, mapOf("sqi" to "1", "score" to "1", "hr" to "1", "deficit" to "1", "perfused" to "1", "days" to "1", "total" to "1", "flagged" to "1"))
        }
    }

    @Test
    fun `every referenced clip file actually exists on disk`() {
        val allFiles = mutableSetOf<String>()
        for (key in EXPLANATION_KEYS) {
            allFiles += audio.clipsFor(
                key,
                mapOf("sqi" to "50", "score" to "50", "hr" to "72", "deficit" to "10", "perfused" to "80", "days" to "30", "total" to "5", "flagged" to "2"),
            )
        }
        for (path in allFiles) {
            val f = File("src/main/assets/$path")
            assertTrue("missing generated clip: ${f.path}", f.exists())
        }
    }
}
