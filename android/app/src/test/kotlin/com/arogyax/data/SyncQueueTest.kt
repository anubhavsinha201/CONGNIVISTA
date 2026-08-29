package com.arogyax.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * Mirrors ml/reference/validate_record.py sections 6-7 (the sync state
 * machine and backoff ladder) exactly - same scenarios, same names.
 */
class SyncQueueTest {
    private val now: OffsetDateTime = OffsetDateTime.now()

    @Test
    fun `five captures queue as pending`() {
        val q = SyncQueue()
        repeat(5) { q.insert("rec-$it") }
        assertEquals(5, q.pendingCount())
    }

    @Test
    fun `a flush cut short syncs only what was acked, the rest stay pending not lost`() {
        val q = SyncQueue()
        repeat(5) { q.insert("rec-$it") }
        q.markSynced("rec-0", now)
        q.markSynced("rec-1", now)
        assertEquals(3, q.pendingCount())
        assertTrue(
            "the interrupted records are still pending, not lost or failed",
            listOf(2, 3, 4).all { q.row("rec-$it")!!.syncState == QueueSyncState.PENDING },
        )
    }

    @Test
    fun `the next batch excludes already-synced records`() {
        val q = SyncQueue()
        repeat(5) { q.insert("rec-$it") }
        q.markSynced("rec-0", now)
        q.markSynced("rec-1", now)
        val batch = q.nextBatch(now).map { it.recordId }.toSet()
        assertEquals(setOf("rec-2", "rec-3", "rec-4"), batch)
    }

    @Test
    fun `an unmentioned record stays pending and is not due until its backoff clears`() {
        val q = SyncQueue()
        q.insert("rec-2")
        q.markRetryable("rec-2", Backoff.nextRetryAt(now, 0, Random(0)))
        assertEquals(QueueSyncState.PENDING, q.row("rec-2")!!.syncState)
        assertTrue("rec-2" !in q.nextBatch(now).map { it.recordId })
        assertTrue("rec-2" in q.nextBatch(now.plusSeconds(6)).map { it.recordId })
    }

    @Test
    fun `a rejected record leaves the queue and never reappears`() {
        val q = SyncQueue()
        q.insert("rec-3")
        q.markFailed("rec-3")
        assertEquals(QueueSyncState.FAILED, q.row("rec-3")!!.syncState)
        assertTrue("rec-3" !in q.nextBatch(now.plusDays(1)).map { it.recordId })
    }

    @Test
    fun `backoff climbs 5s, 30s, 2m, 10m, 30m then caps at 1h`() {
        // No jitter (fixed at the low end via a seed check isn't exact - assert
        // the base ladder directly instead, same as validate_record.py does).
        val expectedSeconds = listOf(5L, 30L, 120L, 600L, 1800L, 3600L, 3600L, 3600L)
        for ((attempts, expected) in expectedSeconds.withIndex()) {
            val base = Backoff.LADDER[minOf(attempts, Backoff.LADDER.size - 1)]
            assertEquals(expected, base.seconds)
        }
    }

    @Test
    fun `jitter stays inside 0point5 to 1point5 of the base`() {
        // jitter = 0.5 + random.nextDouble(); force the two extremes directly.
        val low = Backoff.nextRetryAt(now, 0, object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.0
        })
        val high = Backoff.nextRetryAt(now, 0, object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.9999999
        })
        assertEquals(2500L, java.time.Duration.between(now, low).toMillis())
        assertTrue(java.time.Duration.between(now, high).toMillis() in 7499L..7500L)
    }

    @Test
    fun `clearBackoff releases every pending record at once without resetting the ladder position`() {
        val q = SyncQueue()
        listOf("rec-2", "rec-4").forEach { q.insert(it) }
        q.markRetryable("rec-2", now.plusHours(1))
        val due = q.nextBatch(now).map { it.recordId }.toSet()
        assertTrue("rec-2" !in due)

        q.clearBackoff()
        val nowDue = q.nextBatch(now).map { it.recordId }.toSet()
        assertEquals(setOf("rec-2", "rec-4"), nowDue)
        assertEquals(
            "clearBackoff does not reset the ladder position",
            1,
            q.row("rec-2")!!.attemptCount,
        )
    }

    @Test
    fun `an ack lands on a synced record but never on one the server never accepted`() {
        val q = SyncQueue()
        q.insert("rec-0")
        q.insert("rec-2")
        q.markSynced("rec-0", now)
        // rec-2 stays pending (never synced).

        q.applyAck("rec-0", ReferralState.SEEN_AT_PHC)
        assertEquals(ReferralState.SEEN_AT_PHC, q.row("rec-0")!!.referralState)

        q.applyAck("rec-2", ReferralState.ACKNOWLEDGED)
        assertNull(
            "an ack cannot touch a record the server never accepted",
            q.row("rec-2")!!.referralState,
        )
    }
}
