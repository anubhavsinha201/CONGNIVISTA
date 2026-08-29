package com.arogyax.data

import java.time.Duration
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * The backoff ladder and per-record queue state machine contracts/sync.md
 * sections 5 and 7 describe, and `ml/reference/validate_record.py`'s
 * `FakeQueue`/`next_retry_at` actually test - the state-machine CONTRACT,
 * not the storage.
 *
 * **Scope, deliberately narrow:** this is NOT a full port of
 * `app/lib/data/sync.dart`'s `SyncEngine` or `app/lib/data/local_store.dart`'s
 * `LocalStore`. Those need a real encrypted store (module 6 - SQLCipher for
 * Android vs Jetpack Security's `EncryptedFile`, a real comparison ticket 019
 * calls for, not made here) and a real HTTP client, neither of which exist
 * yet. What's here is the pure, storage-independent state machine both of
 * those will eventually sit on top of: no I/O, no encryption, no network -
 * just the transition rules, kept in memory. It exists now because it's
 * fully specified and fully testable without either of those bigger pieces,
 * the same reasoning that put [com.arogyax.core.PolicyTypes] ahead of
 * `Policy.decide()`.
 */
object Backoff {
    /** contracts/sync.md section 7. Per record, persisted by whatever the real store turns out to be. */
    val LADDER: List<Duration> = listOf(
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        Duration.ofMinutes(2),
        Duration.ofMinutes(10),
        Duration.ofMinutes(30),
        Duration.ofHours(1),
    )

    const val BATCH_SIZE = 25

    /**
     * Next attempt time for a record that has already been tried [attempts]
     * times, with jitter in [0.5, 1.5).
     *
     * The jitter is not cosmetic: a van carrying four workers home hits the
     * same tower at the same second; without it their four phones retry in
     * lockstep and keep colliding on every subsequent rung of the ladder.
     */
    fun nextRetryAt(now: OffsetDateTime, attempts: Int, random: Random = Random.Default): OffsetDateTime {
        val base = LADDER[minOf(attempts, LADDER.size - 1)]
        val jitter = 0.5 + random.nextDouble()
        return now.plus(Duration.ofMillis((base.toMillis() * jitter).toLong()))
    }
}

enum class QueueSyncState { PENDING, SYNCED, FAILED }

/**
 * One record's position in the queue - the in-memory mirror of
 * `FakeQueue.QueuedRecord`, extended to carry the actual [record] payload
 * (not just its ID) now that [SyncEngine] needs something real to upload.
 */
data class QueuedRecord(
    val recordId: String,
    val record: ScreeningRecord,
    var syncState: QueueSyncState = QueueSyncState.PENDING,
    var attemptCount: Int = 0,
    var nextRetryAt: OffsetDateTime? = null,
    var syncedAt: OffsetDateTime? = null,
    var referralState: ReferralState? = null,
)

/**
 * In-memory reference implementation of the queue state machine. Not a
 * substitute for a real encrypted store - see this file's header comment -
 * but the same state transitions any real implementation must honor.
 */
class SyncQueue {
    private val rows = mutableMapOf<String, QueuedRecord>()

    fun insert(record: ScreeningRecord) {
        rows[record.recordId] = QueuedRecord(record.recordId, record)
    }

    fun row(recordId: String): QueuedRecord? = rows[recordId]

    fun nextBatch(now: OffsetDateTime, limit: Int = Backoff.BATCH_SIZE): List<QueuedRecord> =
        rows.values
            .filter { it.syncState == QueueSyncState.PENDING && (it.nextRetryAt == null || !it.nextRetryAt!!.isAfter(now)) }
            .take(limit)

    fun pendingCount(): Int = rows.values.count { it.syncState == QueueSyncState.PENDING }

    fun markSynced(recordId: String, at: OffsetDateTime) {
        val r = rows.getValue(recordId)
        r.syncState = QueueSyncState.SYNCED
        r.syncedAt = at
        r.nextRetryAt = null
    }

    fun markFailed(recordId: String) {
        val r = rows.getValue(recordId)
        r.syncState = QueueSyncState.FAILED
        r.attemptCount += 1
        r.nextRetryAt = null
    }

    fun markRetryable(recordId: String, nextRetryAt: OffsetDateTime) {
        val r = rows.getValue(recordId)
        r.attemptCount += 1
        r.nextRetryAt = nextRetryAt
        r.syncState = QueueSyncState.PENDING
    }

    /**
     * For a connectivity transition and the worker's manual "sync now". The
     * ladder exists for a server that is refusing; it should not keep a
     * record waiting 30 minutes when the radio has just come back and the
     * previous failure was only ever an absent network.
     */
    fun clearBackoff() {
        for (r in rows.values) {
            if (r.syncState == QueueSyncState.PENDING) r.nextRetryAt = null
        }
    }

    /**
     * Scoped to synced rows, exactly as the real store's `applyAcks` must
     * be: an ack for a record the server never actually accepted must not
     * be recorded as if it had been.
     */
    fun applyAck(recordId: String, state: ReferralState) {
        val r = rows[recordId] ?: return
        if (r.syncState == QueueSyncState.SYNCED) r.referralState = state
    }
}
