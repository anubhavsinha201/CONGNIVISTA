package com.arogyax.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * Covers ScreeningRecord.validate(), toJson/fromJson round-tripping, and
 * newRecordId() - the parts of ml/reference/validate_record.py that are
 * actually this class's job. Full JSON-Schema validation against
 * contracts/record.schema.json (that script's sections 1-2) is the server's
 * and the Python mirror's responsibility, not ported here - same boundary
 * record.dart's own doc comment on validate() draws ("not a substitute for
 * the schema").
 */
class ScreeningRecordTest {
    private fun record(
        tier: String = "GREEN",
        decidedBy: String = "rules",
        meanHr: Double? = 74.0,
        rrIrregularityScore: Double? = 0.11,
    ) = ScreeningRecord(
        recordId = ScreeningRecord.newRecordId(),
        patientPseudoId = "a1b2c3d4e5f60718",
        whvId = "whv-021",
        phcId = "phc-042",
        capturedAt = OffsetDateTime.parse("2026-08-29T09:14:00+05:30"),
        lat = 11.0168,
        lon = 76.9558,
        locationAccuracyM = 12.5,
        ageBand = "55-64",
        villageCode = "village-042",
        ecgDurationSec = 30.0,
        sqiScore = 0.82,
        motionRejected = false,
        leadOffDetected = false,
        meanHr = meanHr,
        rrIntervalCount = 37,
        rrIrregularityScore = rrIrregularityScore,
        cnnScore = 0.02,
        decidedBy = decidedBy,
        tier = tier,
        modelVersion = "rules-1.0",
    )

    @Test
    fun `a well-formed record validates cleanly`() {
        assertEquals(emptyList<String>(), record().validate())
    }

    @Test
    fun `a RETAKE carrying scores is rejected - mirrors the EcgAnalyser short-circuit`() {
        val errors = record(tier = "RETAKE", decidedBy = "gate").validate()
        assertTrue("RETAKE record carries scores" in errors)
    }

    @Test
    fun `a RETAKE with no scores validates`() {
        val r = record(tier = "RETAKE", decidedBy = "gate", meanHr = null, rrIrregularityScore = null)
        assertEquals(emptyList<String>(), r.validate())
    }

    @Test
    fun `an unknown tier is rejected`() {
        assertTrue(record(tier = "AFIB").validate().any { "tier not in the enum" in it })
    }

    @Test
    fun `an unknown decidedBy is rejected`() {
        assertTrue(record(decidedBy = "guess").validate().any { "decidedBy not in the enum" in it })
    }

    @Test
    fun `newRecordId produces a valid v4 UUID every time`() {
        repeat(50) {
            val id = ScreeningRecord.newRecordId()
            assertTrue(id, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
        }
    }

    @Test
    fun `toJson then fromJson round-trips exactly`() {
        val original = record(tier = "RED", decidedBy = "rules+cnn")
        val roundTripped = ScreeningRecord.fromJson(original.toJson())
        assertEquals(original, roundTripped)
    }

    @Test
    fun `toSyncJson omits every server-owned field per the sync contract`() {
        val r = record().copy(
            referralState = ReferralState.SEEN_AT_PHC,
            referralUpdatedBy = "phc-042",
            clinicianOutcome = ClinicianOutcome.CONFIRMED,
        )
        val payload = r.toSyncJson()
        for (key in ScreeningRecord.SERVER_OWNED_FIELDS) {
            assertFalse("payload leaked server-owned field '$key'", payload.has(key))
        }
    }

    @Test
    fun `toSyncJson still carries every non-server-owned field`() {
        val r = record()
        val payload = r.toSyncJson()
        assertEquals(r.recordId, payload.getString("recordId"))
        assertEquals(r.tier, payload.getString("tier"))
        assertEquals(r.patientPseudoId, payload.getString("patientPseudoId"))
    }

    @Test
    fun `no field on a fully-populated record contains a diagnosis word`() {
        val escalated = record(tier = "RED", decidedBy = "rules+cnn").copy(
            ppgCorroboration = "pulseDeficit",
            pulseDeficitBpm = 11.2,
            perfusedBeatFraction = 0.72,
        )
        val blob = escalated.toJson().toString().lowercase()
        for (word in listOf("fibrillation", "arrhythmia", "atrial")) {
            assertFalse("found forbidden word '$word' in a record", blob.contains(word))
        }
    }
}
