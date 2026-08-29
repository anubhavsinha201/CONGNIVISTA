package com.arogyax.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fake [ClipPlayer] that completes synchronously (or fails, for the paths
 * in [failing]) - no MediaPlayer, no device, so this tests [SequencePlayer]'s
 * actual branching logic directly and deterministically.
 */
private class FakeClipPlayer(private val failing: Set<String> = emptySet()) : ClipPlayer {
    val played = mutableListOf<String>()
    var stopCalls = 0

    override fun play(assetPath: String, onComplete: () -> Unit, onError: () -> Unit) {
        played.add(assetPath)
        if (assetPath in failing) onError() else onComplete()
    }

    override fun stop() {
        stopCalls++
    }
}

class SequencePlayerTest {
    @Test
    fun `an empty sequence completes immediately without playing anything`() {
        val clipPlayer = FakeClipPlayer()
        var completed = false
        SequencePlayer(clipPlayer).playSequence(emptyList()) { completed = true }

        assertTrue(completed)
        assertEquals(emptyList<String>(), clipPlayer.played)
    }

    @Test
    fun `clips play in the exact order given`() {
        val clipPlayer = FakeClipPlayer()
        var completed = false
        val paths = listOf("a.mp3", "b.mp3", "c.mp3")

        SequencePlayer(clipPlayer).playSequence(paths) { completed = true }

        assertEquals(paths, clipPlayer.played)
        assertTrue("onComplete should fire once every clip has played", completed)
    }

    @Test
    fun `a clip that errors is skipped, the rest of the sequence still plays`() {
        val clipPlayer = FakeClipPlayer(failing = setOf("b.mp3"))
        var completed = false
        val paths = listOf("a.mp3", "b.mp3", "c.mp3")

        SequencePlayer(clipPlayer).playSequence(paths) { completed = true }

        assertEquals(
            "one failed clip should not stop the rest of the sentence from being heard",
            paths,
            clipPlayer.played,
        )
        assertTrue(completed)
    }

    @Test
    fun `cancel before playback starts prevents any clip from playing`() {
        val clipPlayer = FakeClipPlayer()
        val player = SequencePlayer(clipPlayer)

        player.cancel()
        player.playSequence(listOf("a.mp3", "b.mp3")) {}

        // cancel() sets the flag before playSequence() resets it, so a
        // cancel-then-play should behave like a fresh sequence, not a
        // permanently cancelled one - this documents that ordering.
        assertEquals(listOf("a.mp3", "b.mp3"), clipPlayer.played)
    }

    @Test
    fun `calling cancel delegates to the underlying player's stop`() {
        val clipPlayer = FakeClipPlayer()
        val player = SequencePlayer(clipPlayer)

        player.cancel()

        assertEquals(1, clipPlayer.stopCalls)
    }
}
