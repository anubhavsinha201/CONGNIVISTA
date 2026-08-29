package com.arogyax.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TierAudioClipsTest {
    @Test
    fun `every tier maps to a distinct asset path`() {
        val paths = Tier.values().map { TierAudioClips.assetPathFor(it) }
        assertEquals(Tier.values().size, paths.toSet().size)
    }

    @Test
    fun `the asset each path names actually exists and is a real MP3`() {
        for (tier in Tier.values()) {
            val f = File("src/main/assets/${TierAudioClips.assetPathFor(tier)}")
            assertTrue("missing asset for $tier: ${f.path}", f.exists())
            val header = f.readBytes().copyOfRange(0, 3)
            assertEquals("ID3", String(header))
        }
        val supporting = File("src/main/assets/${TierAudioClips.SUPPORTING_LINE_ASSET}")
        assertTrue(supporting.exists())
    }
}
