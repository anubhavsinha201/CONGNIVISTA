package com.arogyax.core

import com.arogyax.data.PatientHistory
import com.arogyax.data.TimelineEntry

/**
 * Longitudinal screening priority and risk trajectory — advanced spec sections
 * 2, 3 and 5.
 *
 * ## The one property that makes this safe
 *
 * **This engine can raise a priority above the tier [Policy] chose. It can never
 * lower one.** [escalateOnly] is the whole guarantee, and
 * `RiskEngineTest` asserts it exhaustively over every tier/history combination
 * rather than trusting the branches below to be written correctly.
 *
 * The reason is section 2.6's rule, generalised: history exists to notice a
 * pattern a single visit cannot show. The moment it can also *suppress* a
 * finding, a patient whose readings have "always looked like that" stops being
 * screened. A screening instrument may never acquire a new way to reassure.
 *
 * ## What it is not
 *
 * Not a diagnosis, and not a probability. [ScreeningPriority] is a queue order
 * for a clinician's attention. Section 5.4 is explicit that this must not be
 * presented as clinical AF burden, so nothing here is named "burden" and the
 * reasons below say "repeated screening abnormality", never a condition.
 *
 * The weighting is rule-based on purpose: section 2.4 lists sequence models as
 * candidates *for a sufficiently large labelled longitudinal dataset*, and no
 * such dataset exists in this build. Rules that can be read and argued with beat
 * a model fitted to data we do not have.
 */
enum class ScreeningPriority { ROUTINE, REPEAT, REFERRAL, PRIORITY_REVIEW }

enum class RiskTrajectory {
    /** Fewer than [PatientHistory.MIN_SCREENINGS_FOR_BURDEN] scored visits. */
    INSUFFICIENT_DATA,
    STABLE,
    IMPROVING,
    FLUCTUATING,
    INCREASING,
    REPEATEDLY_SUSPICIOUS,
}

data class RiskAssessment(
    val priority: ScreeningPriority,
    val trajectory: RiskTrajectory,
    /** Static keys plus values — never a generated sentence. Non-negotiable 7. */
    val reasons: List<Reason>,
    /** True when [priority] is more urgent than this visit's tier alone would give. */
    val raisedByHistory: Boolean,
)

object RiskEngine {

    /** Consecutive flagged visits that constitute "repeatedly suspicious" (spec 5.3). */
    const val CONSECUTIVE_SUSPICIOUS = 3

    /**
     * Fraction of a patient's own baseline HR that counts as a notable
     * deviation (spec 2.6). PROVISIONAL — nothing has been fitted to this.
     * It can only ever add a reason, never change a priority.
     */
    const val BASELINE_HR_DEVIATION = 0.25

    /** Screening priority implied by this visit's tier alone. */
    fun priorityOf(tier: Tier): ScreeningPriority = when (tier) {
        Tier.RED -> ScreeningPriority.PRIORITY_REVIEW
        Tier.ORANGE -> ScreeningPriority.REFERRAL
        Tier.YELLOW -> ScreeningPriority.REFERRAL
        Tier.RETAKE -> ScreeningPriority.REPEAT
        Tier.GREEN -> ScreeningPriority.ROUTINE
    }

    /** The more urgent of two priorities. The only way a priority ever changes. */
    fun escalateOnly(a: ScreeningPriority, b: ScreeningPriority): ScreeningPriority =
        if (b.ordinal > a.ordinal) b else a

    fun assess(decision: TierDecision, history: PatientHistory): RiskAssessment {
        val base = priorityOf(decision.tier)
        val reasons = mutableListOf<Reason>()

        // A refused window is not evidence of anything. It must not be fed into
        // a trajectory, and history must not turn it into a referral: the
        // correct next action is still "capture again".
        if (decision.tier == Tier.RETAKE) {
            return RiskAssessment(
                priority = ScreeningPriority.REPEAT,
                trajectory = trajectoryOf(history),
                reasons = listOf(Reason("risk_retake_not_scored")),
                raisedByHistory = false,
            )
        }

        val trajectory = trajectoryOf(history)
        var priority = base

        if (history.isPersistent) {
            priority = escalateOnly(priority, ScreeningPriority.REFERRAL)
            reasons += Reason("risk_persistent_pattern")
        }
        if (history.isIntermittent) {
            priority = escalateOnly(priority, ScreeningPriority.REFERRAL)
            reasons += Reason("risk_intermittent_pattern")
        }
        if (trajectory == RiskTrajectory.REPEATEDLY_SUSPICIOUS) {
            priority = escalateOnly(priority, ScreeningPriority.REFERRAL)
            reasons += Reason("risk_repeated_suspicious")
        }
        if (trajectory == RiskTrajectory.INCREASING) {
            reasons += Reason("risk_trajectory_increasing")
        }

        // An unresolved referral is a workflow failure, not a clinical finding,
        // but it is exactly the case where a patient falls through the gap: they
        // were flagged, nobody closed the loop, and here they are again.
        if (history.hasLapsedReferral) {
            priority = escalateOnly(priority, ScreeningPriority.PRIORITY_REVIEW)
            reasons += Reason("risk_referral_lapsed")
        } else if (history.hasOpenReferral) {
            priority = escalateOnly(priority, ScreeningPriority.REFERRAL)
            reasons += Reason("risk_referral_open")
        }

        if (history.hasConfirmedFinding) {
            reasons += Reason("risk_previously_confirmed")
        }

        // Section 2.6. Reported, never acted on - see the class doc.
        baselineDeviation(history)?.let { reasons += it }

        if (reasons.isEmpty()) reasons += Reason("risk_no_history_factors")

        return RiskAssessment(
            priority = priority,
            trajectory = trajectory,
            reasons = reasons,
            raisedByHistory = priority != base,
        )
    }

    fun trajectoryOf(history: PatientHistory): RiskTrajectory {
        val scored = history.scored
        if (scored.size < PatientHistory.MIN_SCREENINGS_FOR_BURDEN) {
            return RiskTrajectory.INSUFFICIENT_DATA
        }

        // PatientHistory.timeline is newest-first.
        if (scored.take(CONSECUTIVE_SUSPICIOUS).size == CONSECUTIVE_SUSPICIOUS &&
            scored.take(CONSECUTIVE_SUSPICIOUS).all { it.flagged }
        ) {
            return RiskTrajectory.REPEATEDLY_SUSPICIOUS
        }

        val half = scored.size / 2
        val recent = scored.take(half).count { it.flagged }.toDouble() / half
        val older = scored.drop(half).let { o -> o.count { it.flagged }.toDouble() / o.size }

        return when {
            recent > older -> RiskTrajectory.INCREASING
            recent < older -> RiskTrajectory.IMPROVING
            scored.any { it.flagged } && scored.any { !it.flagged } -> RiskTrajectory.FLUCTUATING
            else -> RiskTrajectory.STABLE
        }
    }

    /**
     * Spec 2.6. Returns a reason when the latest rate departs from this
     * patient's own history, or null when it does not — or when there is not
     * enough history to have a baseline worth comparing against.
     */
    fun baselineDeviation(history: PatientHistory): Reason? {
        val rates = history.scored.mapNotNull(TimelineEntry::meanHr)
        if (rates.size < PatientHistory.MIN_SCREENINGS_FOR_BURDEN) return null

        val latest = rates.first()
        val prior = rates.drop(1)
        if (prior.isEmpty()) return null
        val baseline = prior.average()
        if (baseline <= 0.0) return null

        val delta = (latest - baseline) / baseline
        if (kotlin.math.abs(delta) < BASELINE_HR_DEVIATION) return null

        return Reason(
            if (delta > 0) "risk_baseline_hr_above" else "risk_baseline_hr_below",
            mapOf(
                "baseline" to baseline.toInt().toString(),
                "current" to latest.toInt().toString(),
            ),
        )
    }
}
