package com.arogyax.data

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** One entry in a patient's screening timeline. */
data class TimelineEntry(
    val recordId: String,
    val capturedAt: OffsetDateTime,
    /** RED / ORANGE / YELLOW / GREEN / RETAKE */
    val tier: String,
    val meanHr: Double? = null,
    val rrIrregularityScore: Double? = null,
    val referralState: ReferralState? = null,
    val outcome: ClinicianOutcome? = null,
) {
    /** True when this screening produced a usable answer. RETAKE did not. */
    val scored: Boolean get() = tier != "RETAKE"

    /** True when the rhythm was flagged. RETAKE is not a negative - it is silence. */
    val flagged: Boolean get() = tier == "RED" || tier == "ORANGE" || tier == "YELLOW"
}

/** How confident we can be in an AF-burden figure, given how much was sampled. */
enum class BurdenConfidence { INSUFFICIENT, PROVISIONAL, USABLE }

/**
 * A patient's screening history, reduced to the quantities a WHV or a PHC
 * clinician can act on.
 *
 * This exists because atrial fibrillation is intermittent, so a single strip
 * misses cases, and the clinical answer - a 14-day patch monitor - is
 * impractical at a doorstep. The scheme already revisits the same households
 * for BP and glucose, so the repeat visits themselves are the sampling
 * strategy. That argument is only true if something actually looks across
 * visits. This is that something.
 *
 * Port of app/lib/data/patient_history.dart's PatientHistory - keep the two
 * in sync. Has no dependency on Policy - `tier` is compared as a plain
 * string, matching [ScreeningRecord].
 */
class PatientHistory(
    val patientPseudoId: String,
    /** Newest first. */
    val timeline: List<TimelineEntry>,
) {
    val totalScreenings: Int get() = timeline.size

    /**
     * Screenings that produced a tier. RETAKEs are excluded from every rate
     * below: a refused window is missing data, not a negative result, and
     * counting it as "not AF" would quietly deflate the burden figure.
     */
    val scored: List<TimelineEntry> get() = timeline.filter { it.scored }

    val retakeCount: Int get() = timeline.size - scored.size

    val firstScreening: OffsetDateTime? get() = timeline.lastOrNull()?.capturedAt
    val lastScreening: OffsetDateTime? get() = timeline.firstOrNull()?.capturedAt

    /** Days between the first and most recent screening. */
    val observationDays: Long
        get() {
            if (timeline.size < 2) return 0
            return ChronoUnit.DAYS.between(firstScreening, lastScreening)
        }

    val flaggedCount: Int get() = scored.count { it.flagged }

    /**
     * Fraction of scored screenings that were flagged.
     *
     * **This is not the clinical "AF burden".** True AF burden is the
     * proportion of *time* spent in atrial fibrillation, measured by
     * continuous monitoring. This is the proportion of *sampled 30-second
     * windows* that were flagged, from visits days or weeks apart. Never
     * quote it as a burden percentage to a clinician.
     */
    val flagRate: Double get() = if (scored.isEmpty()) 0.0 else flaggedCount.toDouble() / scored.size

    /**
     * Flagged on some visits but not others - the signature of a paroxysmal
     * rhythm, and the thing a single clinic ECG is most likely to miss.
     */
    val isIntermittent: Boolean
        get() = scored.size >= 2 && flaggedCount > 0 && flaggedCount < scored.size

    /** Every scored visit flagged, with enough visits to mean something. */
    val isPersistent: Boolean
        get() = scored.size >= MIN_SCREENINGS_FOR_BURDEN && flaggedCount == scored.size

    val burdenConfidence: BurdenConfidence
        get() = when {
            scored.size < MIN_SCREENINGS_FOR_BURDEN -> BurdenConfidence.INSUFFICIENT
            observationDays < MIN_OBSERVATION_DAYS -> BurdenConfidence.PROVISIONAL
            else -> BurdenConfidence.USABLE
        }

    /** Worst tier ever recorded for this patient. */
    val worstTier: String
        get() = when {
            scored.any { it.tier == "RED" } -> "RED"
            scored.any { it.tier == "ORANGE" } -> "ORANGE"
            scored.any { it.tier == "YELLOW" } -> "YELLOW"
            scored.isNotEmpty() -> "GREEN"
            else -> "RETAKE"
        }

    /**
     * True when a clinician has confirmed AF at least once. Once true the
     * patient is a known case and screening is no longer the question -
     * follow-up is. A GREEN screening must never be presented as reassurance
     * for someone already confirmed.
     */
    val hasConfirmedFinding: Boolean
        get() = timeline.any { it.outcome == ClinicianOutcome.CONFIRMED }

    /** Referred at least once and the PHC has not closed it. */
    val hasOpenReferral: Boolean
        get() = timeline.any {
            it.flagged && it.referralState != null &&
                it.referralState != ReferralState.NONE && it.referralState != ReferralState.CLOSED
        }

    /**
     * A patient was referred, but no visit ever reached the PHC - the number
     * that tells a district officer whether the referral chain is actually
     * working.
     */
    val hasLapsedReferral: Boolean
        get() = timeline.any {
            it.flagged &&
                (it.referralState == null || it.referralState == ReferralState.NONE) &&
                ChronoUnit.DAYS.between(it.capturedAt, OffsetDateTime.now()) > REFERRAL_LAPSE_DAYS
        }

    /**
     * Days until this patient should be screened again. Uses only what is
     * already known - the worst tier seen, whether the rhythm comes and
     * goes, whether a referral is outstanding. PROVISIONAL: operational
     * judgement, not a validated schedule (CLAUDE.md non-negotiable 8).
     */
    val recommendedRepeatDays: Int
        get() {
            if (timeline.isEmpty()) return 0
            if (hasConfirmedFinding) return INTERVAL_CONFIRMED
            if (hasOpenReferral) return INTERVAL_OPEN_REFERRAL
            // An intermittent rhythm is precisely the case a single strip
            // misses, so sample it more often.
            if (isIntermittent) return INTERVAL_INTERMITTENT
            return when (worstTier) {
                "RED" -> INTERVAL_AFTER_RED
                "ORANGE" -> INTERVAL_AFTER_ORANGE
                "YELLOW" -> INTERVAL_AFTER_YELLOW
                "RETAKE" -> INTERVAL_AFTER_RETAKE
                else -> INTERVAL_ROUTINE
            }
        }

    /** Days until the next screening is due. Negative means overdue. */
    val daysUntilDue: Long?
        get() {
            val last = lastScreening ?: return null
            val due = last.plusDays(recommendedRepeatDays.toLong())
            return ChronoUnit.DAYS.between(OffsetDateTime.now(), due)
        }

    val isDue: Boolean get() = (daysUntilDue ?: 1) <= 0

    /** Machine-readable reason the interval is what it is. Never a condition name. */
    val repeatReasonKey: String
        get() {
            if (timeline.isEmpty()) return "never_screened"
            if (hasConfirmedFinding) return "under_clinician_care"
            if (hasOpenReferral) return "referral_open"
            if (isIntermittent) return "varies_between_visits"
            return when (worstTier) {
                "RED" -> "previous_urgent_referral"
                "ORANGE" -> "previous_repeated_finding"
                "YELLOW" -> "previous_referral"
                "RETAKE" -> "last_capture_unusable"
                else -> "routine"
            }
        }

    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("patientPseudoId", patientPseudoId)
        j.put("totalScreenings", totalScreenings)
        j.put("scoredScreenings", scored.size)
        j.put("retakeCount", retakeCount)
        j.putOpt("firstScreening", firstScreening?.toString())
        j.putOpt("lastScreening", lastScreening?.toString())
        j.put("observationDays", observationDays)
        j.put("flaggedCount", flaggedCount)
        j.put("flagRate", flagRate)
        j.put("burdenConfidence", burdenConfidence.name.lowercase())
        j.put("isIntermittent", isIntermittent)
        j.put("isPersistent", isPersistent)
        j.put("worstTier", worstTier)
        j.put("hasConfirmedFinding", hasConfirmedFinding)
        j.put("hasOpenReferral", hasOpenReferral)
        j.put("hasLapsedReferral", hasLapsedReferral)
        j.put("recommendedRepeatDays", recommendedRepeatDays)
        j.putOpt("daysUntilDue", daysUntilDue)
        j.put("isDue", isDue)
        j.put("repeatReasonKey", repeatReasonKey)
        return j
    }

    companion object {
        /** Below this many scored screenings, a flag rate is a coin flip described with a decimal point. */
        const val MIN_SCREENINGS_FOR_BURDEN = 3

        /** How far apart visits must be before the sample counts as longitudinal rather than one sitting. */
        const val MIN_OBSERVATION_DAYS = 14

        const val REFERRAL_LAPSE_DAYS = 14

        const val INTERVAL_CONFIRMED = 90
        const val INTERVAL_OPEN_REFERRAL = 14
        const val INTERVAL_INTERMITTENT = 30
        const val INTERVAL_AFTER_RED = 14
        const val INTERVAL_AFTER_ORANGE = 21
        const val INTERVAL_AFTER_YELLOW = 45
        const val INTERVAL_AFTER_RETAKE = 7
        const val INTERVAL_ROUTINE = 180

        /** Builds a history from this patient's records. Order of input is irrelevant. */
        fun fromRecords(patientPseudoId: String, records: Iterable<ScreeningRecord>): PatientHistory {
            val mine = records
                .filter { it.patientPseudoId == patientPseudoId }
                .map {
                    TimelineEntry(
                        recordId = it.recordId,
                        capturedAt = it.capturedAt,
                        tier = it.tier,
                        meanHr = it.meanHr,
                        rrIrregularityScore = it.rrIrregularityScore,
                        referralState = it.referralState,
                        outcome = it.clinicianOutcome,
                    )
                }
                .sortedByDescending { it.capturedAt }
            return PatientHistory(patientPseudoId, mine)
        }

        /** Groups a mixed set of records into one history per patient. */
        fun groupAll(records: Iterable<ScreeningRecord>): Map<String, PatientHistory> {
            val ids = records.map { it.patientPseudoId }.toSet()
            return ids.associateWith { fromRecords(it, records) }
        }

        /** Patients due for a repeat visit, most overdue first - the WHV's round. */
        fun dueList(records: Iterable<ScreeningRecord>): List<PatientHistory> =
            groupAll(records).values
                .filter { it.isDue }
                .sortedBy { it.daysUntilDue ?: 0 }
    }
}
