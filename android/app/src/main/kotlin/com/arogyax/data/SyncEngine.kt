package com.arogyax.data

import java.time.OffsetDateTime
import kotlin.random.Random

/** Outcome of one flush, for the UI and for tests. */
data class SyncReport(
    val accepted: Int = 0,
    val rejected: Int = 0,
    val retryable: Int = 0,
    val acksApplied: Int = 0,
    val error: Throwable? = null,
) {
    val madeProgress: Boolean get() = accepted > 0 || acksApplied > 0
}

/**
 * Opportunistic flush of the local queue to the PHC.
 *
 * ## The rule this class exists to respect
 *
 * **Sync never blocks a result.** Nothing here is on the path between a
 * patient and their tier. The capture screen inserts into [queue] and
 * returns; if this engine is broken, throwing, or not running at all, the
 * screening still completed and the record is still safe on disk. Every
 * method below can fail without a patient noticing.
 *
 * Sits on top of [SyncQueue] (module 7's pure state machine) and
 * [SyncClient] (real HTTP, [HttpSyncClient]) - this class holds none of the
 * storage or transport itself, only the orchestration between them. [queue]
 * is still the in-memory reference implementation, not the real encrypted
 * store (module 6, not built) - swapping in a persisted `SyncQueue` later
 * changes nothing here, by design.
 *
 * Port of app/lib/data/sync.dart's SyncEngine - keep the two in sync.
 * See contracts/sync.md.
 */
class SyncEngine(
    private val queue: SyncQueue,
    private val client: SyncClient,
    /** Injected so tests are deterministic. */
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
    private val random: Random = Random.Default,
    ackCursor: OffsetDateTime? = null,
) {
    var ackCursor: OffsetDateTime? = ackCursor
        private set

    @Volatile
    private var running = false

    /** True while a flush is in flight. */
    val isRunning: Boolean get() = running

    /**
     * Drains the queue, then pulls acknowledgements.
     *
     * Re-entrant calls are dropped rather than queued: four triggers can
     * fire within a second of a radio coming up (connectivity event,
     * foreground, timer, the worker tapping "sync now"), and running four
     * concurrent flushes would upload the same batch four times. The
     * upsert makes that harmless server-side, but it wastes the coverage
     * window, which is the scarce resource here.
     */
    fun flush(maxBatches: Int = 20): SyncReport {
        if (running) return SyncReport()
        running = true
        try {
            return doFlush(maxBatches)
        } finally {
            running = false
        }
    }

    private fun doFlush(maxBatches: Int): SyncReport {
        var accepted = 0
        var rejected = 0
        var retryable = 0
        var error: Throwable? = null

        for (i in 0 until maxBatches) {
            val batch = queue.nextBatch(now())
            if (batch.isEmpty()) break

            val results: List<UploadResult>
            try {
                results = client.uploadBatch(batch.map { it.record })
            } catch (e: SyncAuthException) {
                // Backing off will not fix a revoked token. Stop, keep
                // everything pending, and surface it - the phone needs
                // re-provisioning, a human action, not a retry.
                error = e
                break
            } catch (e: SyncTransportException) {
                // The network failed. This says nothing about the records,
                // so the whole batch stays pending and backs off together.
                error = e
                for (r in batch) backOff(r.recordId, code = "transport")
                retryable += batch.size
                break
            }

            val byId = results.associateBy { it.recordId }
            val synced = mutableListOf<String>()
            var rejectedHere = 0

            for (r in batch) {
                // A record the server did not mention is retryable, never
                // accepted. Silence is not an acknowledgement: treating it
                // as one would mark a referral synced that no PHC will ever see.
                val res = byId[r.recordId] ?: UploadResult(r.recordId, UploadStatus.RETRYABLE, code = "no_result")

                when (res.status) {
                    UploadStatus.ACCEPTED -> {
                        synced.add(r.recordId)
                        accepted++
                    }
                    UploadStatus.REJECTED -> {
                        queue.markFailed(r.recordId)
                        rejected++
                        rejectedHere++
                    }
                    UploadStatus.RETRYABLE -> {
                        backOff(r.recordId, res.code)
                        retryable++
                    }
                }
            }

            val syncedAt = now()
            for (id in synced) queue.markSynced(id, syncedAt)

            // Nothing in THIS batch moved: every row in it is now backing
            // off, so the next iteration would fetch the same rows and fail
            // the same way. Stop. Counted per batch, not cumulatively - a
            // rejection three batches ago must not keep this loop alive.
            if (synced.isEmpty() && rejectedHere == 0) break
        }

        val acksApplied = pullAcks()

        return SyncReport(accepted, rejected, retryable, acksApplied, error)
    }

    private fun pullAcks(): Int {
        return try {
            val page = client.fetchAcks(ackCursor)
            for (ack in page.acks) queue.applyAck(ack.recordId, ack.referralState)
            // Only advance the cursor on success. A cursor advanced past
            // acks that were never applied loses them permanently.
            if (page.cursor != null) ackCursor = page.cursor
            page.acks.size
        } catch (e: Exception) {
            // Acks are a convenience for the worker, not part of the
            // referral path. Failing to fetch them must not fail a flush
            // that uploaded records.
            0
        }
    }

    private fun backOff(recordId: String, code: String?) {
        val attempts = queue.row(recordId)?.attemptCount ?: 0
        queue.markRetryable(recordId, Backoff.nextRetryAt(now(), attempts, random))
    }

    /**
     * Clears every pending backoff and flushes immediately. For a
     * connectivity transition and the worker's manual "sync now" - the
     * ladder exists for a server that is refusing; it should not keep a
     * record waiting 30 minutes when the radio has just come back and the
     * previous failure was only ever an absent network.
     */
    fun flushNow(): SyncReport {
        queue.clearBackoff()
        return flush()
    }
}
