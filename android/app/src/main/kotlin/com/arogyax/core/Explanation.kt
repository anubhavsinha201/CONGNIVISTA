package com.arogyax.core

import com.arogyax.data.BurdenConfidence
import com.arogyax.data.PatientHistory
import kotlin.math.roundToInt

/**
 * One reason a tier came out the way it did.
 *
 * A key plus its numbers, never a sentence. The UI looks the key up in the
 * static Tamil/English string table and substitutes the values - reviewable
 * by a clinician, translated in one place, and explainability rather than
 * generation.
 *
 * Port of app/lib/core/explanation.dart's Reason - keep the two in sync.
 */
data class Reason(val key: String, val values: Map<String, String> = emptyMap()) {
    override fun toString(): String = if (values.isEmpty()) key else "$key $values"
}

/**
 * Turns a decision into the reasons behind it.
 *
 * ## Why this is templated and not generated
 *
 * Generated prose is the one thing this product cannot ship: CLAUDE.md
 * non-negotiable 7 forbids model-generated worker-facing text, and
 * non-negotiable 1 forbids anything that reads as a diagnosis. Every input to
 * the decision is already a named number, so the decision can be explained
 * exactly rather than approximately - a generated sentence would be a less
 * faithful account of the same logic, with a new way to be wrong.
 *
 * Port of app/lib/core/explanation.dart's Explainer - keep the two in sync.
 * Uses [PolicyGates], not the full `Policy` - module 3 is paused; see that
 * file's header comment for the exact boundary.
 */
object Explainer {
    /** Reasons for a single screening, most important first. */
    fun forDecision(d: TierDecision, i: TierInputs): List<Reason> {
        val out = mutableListOf<Reason>()

        if (d.tier == Tier.RETAKE) {
            val key = when (d.retakeReason) {
                RetakeReason.ELECTRODE_DETACHED -> "why.retake.electrode"
                RetakeReason.PATIENT_MOVED -> "why.retake.motion"
                RetakeReason.DROPPED_DATA -> "why.retake.connection"
                RetakeReason.POOR_SIGNAL_QUALITY -> "why.retake.quality"
                RetakeReason.TOO_FEW_BEATS -> "why.retake.tooShort"
                RetakeReason.BEAT_DETECTION_UNRELIABLE -> "why.retake.beatDetection"
                null -> "why.retake.generic"
            }
            out.add(Reason(key, mapOf("sqi" to pct(i.sqiScore))))
            return out
        }

        // Rhythm. The primary finding, so it leads.
        val irregular = i.rrIrregularityScore >= PolicyGates.RR_IRREGULARITY_GATE ||
            (i.cnnScore != null && i.cnnScore >= PolicyGates.CNN_THRESHOLD_INT8)

        out.add(
            Reason(
                if (irregular) "why.rhythm.irregular" else "why.rhythm.regular",
                mapOf("score" to pct(i.rrIrregularityScore)),
            ),
        )

        // Which detector, so a clinician can weigh the evidence rather than taking the tier on faith.
        out.add(
            Reason(
                when (d.decidedBy) {
                    DecidedBy.RULES -> "why.source.rules"
                    DecidedBy.CNN -> "why.source.model"
                    DecidedBy.RULES_AND_CNN -> "why.source.both"
                    DecidedBy.GATE -> "why.source.gate"
                    DecidedBy.HISTORY -> "why.source.history"
                },
            ),
        )

        // Rate decides urgency, not presence - say which it did.
        when {
            i.meanHr < PolicyGates.HR_LOW ->
                out.add(Reason("why.rate.low", mapOf("hr" to i.meanHr.roundToInt().toString())))
            i.meanHr > PolicyGates.HR_HIGH ->
                out.add(Reason("why.rate.high", mapOf("hr" to i.meanHr.roundToInt().toString())))
            else ->
                out.add(Reason("why.rate.normal", mapOf("hr" to i.meanHr.roundToInt().toString())))
        }

        // The PPG's contribution, when it had one - what makes a YELLOW/ORANGE-to-RED escalation auditable.
        when (d.ppg) {
            PpgCorroboration.PULSE_DEFICIT ->
                out.add(
                    Reason(
                        "why.ppg.pulseDeficit",
                        mapOf("deficit" to (i.pulseDeficitBpm ?: 0.0).roundToInt().toString()),
                    ),
                )
            PpgCorroboration.NON_PERFUSING_BEATS ->
                out.add(
                    Reason(
                        "why.ppg.nonPerfusing",
                        mapOf("perfused" to pct(i.perfusedBeatFraction ?: 0.0)),
                    ),
                )
            PpgCorroboration.AGREED -> out.add(Reason("why.ppg.agreed"))
            PpgCorroboration.UNUSABLE -> out.add(Reason("why.ppg.unusable"))
            PpgCorroboration.NONE -> {}
        }

        return out
    }

    /** Reasons a patient is due for a repeat visit. Feature 12's "why". */
    fun forRepeat(h: PatientHistory): List<Reason> {
        val out = mutableListOf(
            Reason(
                "why.repeat.${h.repeatReasonKey}",
                mapOf(
                    "days" to h.recommendedRepeatDays.toString(),
                    "overdue" to (-(h.daysUntilDue ?: 0)).coerceIn(0, 99999).toString(),
                ),
            ),
        )

        // Only quote a rate once there is enough sampling behind it. Below
        // that it is a coin flip with a decimal point, and a clinician shown
        // "50% of visits" from two screenings will read far more into it
        // than it holds.
        if (h.burdenConfidence != BurdenConfidence.INSUFFICIENT) {
            out.add(
                Reason(
                    "why.history.flagRate",
                    mapOf(
                        "flagged" to h.flaggedCount.toString(),
                        "total" to h.scored.size.toString(),
                        "days" to h.observationDays.toString(),
                    ),
                ),
            )
        }
        if (h.isIntermittent) out.add(Reason("why.history.intermittent"))
        if (h.hasLapsedReferral) out.add(Reason("why.history.referralLapsed"))
        return out
    }

    private fun pct(v: Double): String = "${(v * 100).roundToInt()}%"
}

/**
 * Every key [Explainer] can emit. The string table must cover all of these
 * in Tamil and English - kept here so a test can assert the table is
 * complete, since a missing key is a blank line on a health worker's screen
 * at a doorstep, discovered in the field.
 */
val EXPLANATION_KEYS: Set<String> = setOf(
    "why.retake.electrode",
    "why.retake.motion",
    "why.retake.connection",
    "why.retake.quality",
    "why.retake.tooShort",
    "why.retake.beatDetection",
    "why.retake.generic",
    "why.rhythm.irregular",
    "why.rhythm.regular",
    "why.source.rules",
    "why.source.model",
    "why.source.both",
    "why.source.gate",
    "why.source.history",
    "why.rate.low",
    "why.rate.high",
    "why.rate.normal",
    "why.ppg.pulseDeficit",
    "why.ppg.nonPerfusing",
    "why.ppg.agreed",
    "why.ppg.unusable",
    "why.repeat.never_screened",
    "why.repeat.under_clinician_care",
    "why.repeat.referral_open",
    "why.repeat.varies_between_visits",
    "why.repeat.previous_urgent_referral",
    "why.repeat.previous_repeated_finding",
    "why.repeat.previous_referral",
    "why.repeat.last_capture_unusable",
    "why.repeat.routine",
    "why.history.flagRate",
    "why.history.intermittent",
    "why.history.referralLapsed",
)
