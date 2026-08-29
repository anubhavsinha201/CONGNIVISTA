package com.arogyax.data

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import java.time.OffsetDateTime

/**
 * Real integration test against a live `server/` instance - not mocked, not
 * a fake `SyncClient`. Requires `cd server && npm start` running on
 * :8787 first (Atlas-backed or DEMO=1, either works - this only needs the
 * seed device token, which both modes provision identically).
 *
 * `@Ignore`d from the default `gradle test` run on purpose: every other
 * suite in this project runs hermetically, no device, no network
 * (CLAUDE.md's whole reason the DSP/policy layer depends on nothing beyond
 * the standard library) - this one genuinely needs a live server, so it
 * would break that property for everyone if it ran by default. Run it
 * deliberately (IDE "run test", or `gradle test --tests
 * "*SyncEngineIntegrationTest*"` after removing the annotation) when you
 * want to prove the real HTTP path still works, the same way
 * `ml/reference/export_replay_trace.py`-style scripts are meant to be run
 * on demand, not on every build.
 */
@Ignore("requires a live server on :8787 - see class doc comment")
class SyncEngineIntegrationTest {
    private val seedToken = "dev-token-whv-021"
    private val baseUrl = "http://127.0.0.1:8787"

    private fun testRecord() = ScreeningRecord(
        recordId = ScreeningRecord.newRecordId(),
        patientPseudoId = "a1b2c3d4e5f60718",
        whvId = "whv-021", // must match the seed device's whvId or the server 403s the whole batch
        phcId = "phc-042",
        capturedAt = OffsetDateTime.now(),
        ageBand = "55-64",
        villageCode = "village-042",
        ecgDurationSec = 30.0,
        sqiScore = 0.9,
        motionRejected = false,
        leadOffDetected = false,
        meanHr = 74.0,
        rrIntervalCount = 40,
        rrIrregularityScore = 0.1,
        decidedBy = "rules",
        tier = "GREEN",
        modelVersion = "rules-1.0",
    )

    @Test
    fun `a real flush against the live server actually syncs the record`() {
        val client = HttpSyncClient(baseUrl, seedToken)
        val queue = SyncQueue()
        val engine = SyncEngine(queue, client)

        val record = testRecord()
        queue.insert(record)

        val report = engine.flush()

        assertEquals("expected the real server to accept this record: $report", 1, report.accepted)
        assertEquals(QueueSyncState.SYNCED, queue.row(record.recordId)!!.syncState)
    }
}
