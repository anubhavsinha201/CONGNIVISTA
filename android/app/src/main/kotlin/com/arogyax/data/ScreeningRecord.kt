package com.arogyax.data

import org.json.JSONObject
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Device-side upload state. See contracts/sync.md section 5. */
enum class SyncState(val wire: String) {
    PENDING("pending"),
    SYNCED("synced"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(s: String?): SyncState = values().firstOrNull { it.wire == s } ?: PENDING
    }
}

/**
 * What the PHC has done about a referral. Server-owned; the device only ever
 * mirrors it back from `GET /v1/acks`.
 *
 * A closed enum rather than free text, on purpose - worker-facing text comes
 * from the static string table (CLAUDE.md non-negotiable 6), and an
 * unreviewed free-text channel from outside is exactly how the words this
 * product must never display would reach a worker's screen (non-negotiable 1).
 */
enum class ReferralState(val wire: String) {
    NONE("none"),
    ACKNOWLEDGED("acknowledged"),
    PATIENT_CONTACTED("patient_contacted"),
    VISIT_SCHEDULED("visit_scheduled"),
    SEEN_AT_PHC("seen_at_phc"),
    CLOSED("closed"),
    ;

    companion object {
        fun fromWire(s: String?): ReferralState? = values().firstOrNull { it.wire == s }
    }
}

/**
 * What the PHC clinician found after seeing a referred patient.
 *
 * Deliberately separate from [ReferralState]: that records *process* (was the
 * patient contacted), this records the *finding*. Only a finding can be used
 * as a training label.
 */
enum class ClinicianOutcome(val wire: String) {
    CONFIRMED("confirmed"),
    NOT_CONFIRMED("not_confirmed"),

    /**
     * Seen, but the question was not settled. NOT a negative - excluding
     * these from retraining is correct; counting them as negatives would
     * teach the model that intermittent AF is absence of AF.
     */
    INCONCLUSIVE("inconclusive"),
    ;

    /** Whether this record may be used as a supervised training label. */
    val isTrainingLabel: Boolean get() = this == CONFIRMED || this == NOT_CONFIRMED

    companion object {
        fun fromWire(s: String?): ClinicianOutcome? = values().firstOrNull { it.wire == s }
    }
}

/**
 * One doorstep screening, as stored locally and as uploaded.
 *
 * Mirrors contracts/record.schema.json v4, field for field. Immutable - a
 * screening is a historical fact; the only field that legitimately changes
 * after capture is the server-owned referral block, and that arrives by
 * [copyWith] rather than mutation.
 *
 * `tier` and `decidedBy` are plain strings, not enums, matching the wire
 * format directly and matching record.dart - deliberately: this class has no
 * compile-time dependency on Policy's decision types, which is what let it be
 * ported ahead of module 3.
 *
 * Port of app/lib/data/record.dart's ScreeningRecord - keep the two in sync.
 */
data class ScreeningRecord(
    // ---- Identity ----------------------------------------------------------
    val recordId: String,
    val patientPseudoId: String,
    val whvId: String,
    val phcId: String? = null,

    // ---- When and where -----------------------------------------------------
    val capturedAt: OffsetDateTime,
    val lat: Double? = null,
    val lon: Double? = null,
    val locationAccuracyM: Double? = null,

    // ---- Demographics (schema v4) --------------------------------------------
    val ageBand: String,
    val villageCode: String,
    val sex: String? = null,
    val systolicBp: Double? = null,
    val diastolicBp: Double? = null,
    val glucose: Double? = null,

    // ---- Camera / contact PPG -------------------------------------------------
    val ppgResult: String? = null,
    val ppgMeanHr: Double? = null,
    val ppgIrregularityScore: Double? = null,
    val ppgPerfusionIndex: Double? = null,

    // ---- ECG capture and quality ----------------------------------------------
    val ecgDurationSec: Double,
    val sqiScore: Double,
    val motionRejected: Boolean,
    val leadOffDetected: Boolean,

    // ---- Detector outputs -------------------------------------------------------
    val meanHr: Double? = null,
    val rrIntervalCount: Int? = null,
    val rrIrregularityScore: Double? = null,
    val cnnScore: Double? = null,
    val decidedBy: String,

    // ---- Fusion evidence ----------------------------------------------------------
    val pulseDeficitBpm: Double? = null,
    val perfusedBeatFraction: Double? = null,
    val nonPerfusingBeats: Int? = null,
    val medianPttMs: Double? = null,
    val fusionValid: Boolean = false,
    val fusionImplausible: Boolean = false,

    /** Why a YELLOW or ORANGE became a RED, when it did. */
    val ppgCorroboration: String? = null,

    // ---- The answer -------------------------------------------------------------
    val tier: String,
    val modelVersion: String,
    val ecgWaveformRef: String? = null,

    // ---- Device bookkeeping -------------------------------------------------------
    val syncState: SyncState = SyncState.PENDING,
    val syncedAt: OffsetDateTime? = null,

    // ---- Server-owned -------------------------------------------------------------
    val referralState: ReferralState? = null,
    val referralUpdatedAt: OffsetDateTime? = null,
    val referralUpdatedBy: String? = null,
    val clinicianOutcome: ClinicianOutcome? = null,
    val clinicianOutcomeAt: OffsetDateTime? = null,
) {
    /**
     * Full record, including the server-owned referral block. Used for local
     * storage. **Not** what goes on the wire - see [toSyncJson].
     */
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("recordId", recordId)
        j.put("schemaVersion", SCHEMA_VERSION)
        j.put("patientPseudoId", patientPseudoId)
        j.put("whvId", whvId)
        j.putOpt("phcId", phcId)
        j.put("capturedAt", iso(capturedAt))
        j.putOpt("lat", lat)
        j.putOpt("lon", lon)
        j.putOpt("locationAccuracyM", locationAccuracyM)
        j.put("ageBand", ageBand)
        j.put("villageCode", villageCode)
        j.putOpt("sex", sex)
        j.putOpt("systolicBp", systolicBp)
        j.putOpt("diastolicBp", diastolicBp)
        j.putOpt("glucose", glucose)
        j.putOpt("ppgResult", ppgResult)
        j.putOpt("ppgMeanHr", ppgMeanHr)
        j.putOpt("ppgIrregularityScore", ppgIrregularityScore)
        j.putOpt("ppgPerfusionIndex", ppgPerfusionIndex)
        j.put("ecgDurationSec", ecgDurationSec)
        j.put("sqiScore", sqiScore)
        j.put("motionRejected", motionRejected)
        j.put("leadOffDetected", leadOffDetected)
        j.putOpt("meanHr", meanHr)
        j.putOpt("rrIntervalCount", rrIntervalCount)
        j.putOpt("rrIrregularityScore", rrIrregularityScore)
        j.putOpt("cnnScore", cnnScore)
        j.put("decidedBy", decidedBy)
        j.putOpt("pulseDeficitBpm", pulseDeficitBpm)
        j.putOpt("perfusedBeatFraction", perfusedBeatFraction)
        j.putOpt("nonPerfusingBeats", nonPerfusingBeats)
        j.putOpt("medianPttMs", medianPttMs)
        j.put("fusionValid", fusionValid)
        j.put("fusionImplausible", fusionImplausible)
        j.putOpt("ppgCorroboration", ppgCorroboration)
        j.put("tier", tier)
        j.put("modelVersion", modelVersion)
        j.putOpt("ecgWaveformRef", ecgWaveformRef)
        j.put("syncState", syncState.wire)
        j.putOpt("syncedAt", syncedAt?.let { iso(it) })
        j.putOpt("referralState", referralState?.wire)
        j.putOpt("referralUpdatedAt", referralUpdatedAt?.let { iso(it) })
        j.putOpt("referralUpdatedBy", referralUpdatedBy)
        j.putOpt("clinicianOutcome", clinicianOutcome?.wire)
        j.putOpt("clinicianOutcomeAt", clinicianOutcomeAt?.let { iso(it) })
        return j
    }

    /**
     * The upload payload: the full record minus the server-owned referral
     * block.
     *
     * A phone that has been out of coverage for six hours holds a stale copy
     * of `referralState`. Sending it would let a retry silently revert an
     * acknowledgement a PHC nurse made an hour ago. The server strips these
     * fields on ingest too - this is the belt, that is the braces.
     */
    fun toSyncJson(): JSONObject {
        val j = toJson()
        for (key in SERVER_OWNED_FIELDS) j.remove(key)
        return j
    }

    /**
     * Cheap structural check, run before a record is queued. Not a
     * substitute for the schema (`ml/reference/validate_record.py` does
     * that properly) - catches the handful of ways a caller can produce a
     * record the server will reject, at the point where the caller can
     * still be blamed for it.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (!UUID_V4.matches(recordId)) errors.add("recordId is not a v4 UUID: $recordId")
        if (patientPseudoId.length < 8) errors.add("patientPseudoId shorter than 8 characters")
        if (whvId.isEmpty()) errors.add("whvId is empty")
        if (ageBand !in VALID_AGE_BANDS) errors.add("ageBand not in the enum: $ageBand")
        if (villageCode.isEmpty()) errors.add("villageCode is empty")
        if (tier !in VALID_TIERS) errors.add("tier not in the enum: $tier")
        if (decidedBy !in VALID_DECIDED_BY) errors.add("decidedBy not in the enum: $decidedBy")
        if (sqiScore < 0 || sqiScore > 1) errors.add("sqiScore outside 0..1")
        if (modelVersion.isEmpty()) errors.add("modelVersion is empty")

        // A gated window must carry no interpretation. Mirrors the
        // short-circuit in EcgAnalyser.analyse; if the two ever disagree, a
        // RETAKE could reach the dashboard with a heart rate attached to it.
        if (tier == "RETAKE" && (meanHr != null || rrIrregularityScore != null)) {
            errors.add("RETAKE record carries scores")
        }
        return errors
    }

    companion object {
        const val SCHEMA_VERSION = 4

        val SERVER_OWNED_FIELDS = setOf(
            "referralState", "referralUpdatedAt", "referralUpdatedBy",
            "clinicianOutcome", "clinicianOutcomeAt",
        )

        private val VALID_AGE_BANDS = setOf("45-54", "55-64", "65-74", "75+")
        private val VALID_TIERS = setOf("RED", "ORANGE", "YELLOW", "GREEN", "RETAKE")
        private val VALID_DECIDED_BY = setOf("rules", "cnn", "rules+cnn", "gate", "history")

        private val UUID_V4 = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

        private val secureRandom = SecureRandom()

        /**
         * ISO 8601 **with a timezone offset**, never a bare UTC `Z` read as
         * ambiguous - `OffsetDateTime.toString()` already always includes
         * the offset, unlike Dart's `DateTime.toIso8601String()` on a local
         * (non-UTC) instance, so no extra handling is needed here.
         */
        private fun iso(t: OffsetDateTime): String = t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        /**
         * RFC 4122 version 4 UUID, matching the pattern in
         * record.schema.json. Hand-rolled rather than pulling in a package,
         * same reasoning as the Dart side: the schema pins the exact shape
         * so a mistake fails a test rather than escaping.
         */
        fun newRecordId(): String {
            val b = ByteArray(16)
            secureRandom.nextBytes(b)
            b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte() // version 4
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte() // variant 10xx
            val hex = b.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-" +
                "${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }

        private fun optDouble(j: JSONObject, key: String): Double? =
            if (j.isNull(key) || !j.has(key)) null else j.getDouble(key)

        private fun optInt(j: JSONObject, key: String): Int? =
            if (j.isNull(key) || !j.has(key)) null else j.getInt(key)

        private fun optString(j: JSONObject, key: String): String? =
            if (j.isNull(key) || !j.has(key)) null else j.getString(key)

        private fun optOffsetDateTime(j: JSONObject, key: String): OffsetDateTime? =
            optString(j, key)?.let { OffsetDateTime.parse(it) }

        fun fromJson(j: JSONObject): ScreeningRecord = ScreeningRecord(
            recordId = j.getString("recordId"),
            patientPseudoId = j.getString("patientPseudoId"),
            whvId = j.getString("whvId"),
            phcId = optString(j, "phcId"),
            capturedAt = OffsetDateTime.parse(j.getString("capturedAt")),
            lat = optDouble(j, "lat"),
            lon = optDouble(j, "lon"),
            locationAccuracyM = optDouble(j, "locationAccuracyM"),
            ageBand = j.getString("ageBand"),
            villageCode = j.getString("villageCode"),
            sex = optString(j, "sex"),
            systolicBp = optDouble(j, "systolicBp"),
            diastolicBp = optDouble(j, "diastolicBp"),
            glucose = optDouble(j, "glucose"),
            ppgResult = optString(j, "ppgResult"),
            ppgMeanHr = optDouble(j, "ppgMeanHr"),
            ppgIrregularityScore = optDouble(j, "ppgIrregularityScore"),
            ppgPerfusionIndex = optDouble(j, "ppgPerfusionIndex"),
            ecgDurationSec = optDouble(j, "ecgDurationSec") ?: 0.0,
            sqiScore = optDouble(j, "sqiScore") ?: 0.0,
            motionRejected = j.optBoolean("motionRejected", false),
            leadOffDetected = j.optBoolean("leadOffDetected", false),
            meanHr = optDouble(j, "meanHr"),
            rrIntervalCount = optInt(j, "rrIntervalCount"),
            rrIrregularityScore = optDouble(j, "rrIrregularityScore"),
            cnnScore = optDouble(j, "cnnScore"),
            decidedBy = j.optString("decidedBy", "gate"),
            pulseDeficitBpm = optDouble(j, "pulseDeficitBpm"),
            perfusedBeatFraction = optDouble(j, "perfusedBeatFraction"),
            nonPerfusingBeats = optInt(j, "nonPerfusingBeats"),
            medianPttMs = optDouble(j, "medianPttMs"),
            fusionValid = j.optBoolean("fusionValid", false),
            fusionImplausible = j.optBoolean("fusionImplausible", false),
            ppgCorroboration = optString(j, "ppgCorroboration"),
            tier = j.getString("tier"),
            modelVersion = j.getString("modelVersion"),
            ecgWaveformRef = optString(j, "ecgWaveformRef"),
            syncState = SyncState.fromWire(optString(j, "syncState")),
            syncedAt = optOffsetDateTime(j, "syncedAt"),
            referralState = ReferralState.fromWire(optString(j, "referralState")),
            referralUpdatedAt = optOffsetDateTime(j, "referralUpdatedAt"),
            referralUpdatedBy = optString(j, "referralUpdatedBy"),
            clinicianOutcome = ClinicianOutcome.fromWire(optString(j, "clinicianOutcome")),
            clinicianOutcomeAt = optOffsetDateTime(j, "clinicianOutcomeAt"),
        )
    }
}

/** One referral state change, pulled from `GET /v1/acks`. */
data class ReferralAck(
    val recordId: String,
    val referralState: ReferralState,
    val referralUpdatedAt: OffsetDateTime? = null,
    val referralUpdatedBy: String? = null,
    val clinicianOutcome: ClinicianOutcome? = null,
    val clinicianOutcomeAt: OffsetDateTime? = null,
) {
    companion object {
        fun fromJson(j: JSONObject): ReferralAck = ReferralAck(
            recordId = j.getString("recordId"),
            referralState = ReferralState.fromWire(j.optString("referralState", null))
                ?: ReferralState.NONE,
            referralUpdatedAt = if (j.isNull("referralUpdatedAt") || !j.has("referralUpdatedAt")) {
                null
            } else {
                OffsetDateTime.parse(j.getString("referralUpdatedAt"))
            },
            referralUpdatedBy = if (j.isNull("referralUpdatedBy")) null else j.optString("referralUpdatedBy", null),
        )
    }
}
