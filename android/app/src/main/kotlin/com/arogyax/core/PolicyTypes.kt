package com.arogyax.core

/**
 * The data types [com.arogyax.core.Policy][Policy] (module 3, paused by user
 * request as of 2026-08-30) will own, ported ahead of it because
 * `Explainer` (module 4) needs them to compile against and they carry no
 * decision logic of their own - just enums and field storage.
 *
 * **This file deliberately does NOT include `Policy.decide()`, `kSqiGate`,
 * `kMinRrIntervals`, `kMotionWanderRatioGate`, `kMotionPerfusionInstabilityGate`,
 * or any gate-ordering logic.** Only the four named constants below exist
 * because `Explainer.forDecision` reads them directly. Do not add more
 * Policy behavior here without updating this file's own scope note - the
 * whole point of drawing the line here is that it stays easy to see exactly
 * how much of Policy exists in Kotlin at a glance.
 *
 * Mirrors the type definitions in app/lib/core/policy.dart - keep in sync.
 */

/** Referral urgency. NOT a diagnosis - see contracts/tiers.md section 1. */
enum class Tier { RETAKE, RED, ORANGE, YELLOW, GREEN }

/** Which path produced the tier. Serialised into the record so the PHC can see what evidence a referral rests on. */
enum class DecidedBy(val wire: String) {
    GATE("gate"),
    RULES("rules"),
    CNN("cnn"),
    RULES_AND_CNN("rules+cnn"),
    HISTORY("history"),
}

/** Why a window was rejected, when [Tier.RETAKE]. */
enum class RetakeReason {
    POOR_SIGNAL_QUALITY,
    PATIENT_MOVED,
    ELECTRODE_DETACHED,
    DROPPED_DATA,
    TOO_FEW_BEATS,

    /** PPG saw more pulses than the ECG saw beats, so the R-peak detector is wrong. */
    BEAT_DETECTION_UNRELIABLE,
}

/** Whether the contact PPG corroborated the ECG's finding. */
enum class PpgCorroboration { NONE, UNUSABLE, AGREED, PULSE_DEFICIT, NON_PERFUSING_BEATS }

data class TierDecision(
    val tier: Tier,
    val decidedBy: DecidedBy,
    val retakeReason: RetakeReason? = null,
    /** Populated only when [tier] is [Tier.RETAKE]; a short hint for the worker about what to physically fix. */
    val retakeHint: String? = null,
    val ppg: PpgCorroboration = PpgCorroboration.NONE,
)

/** Inputs to the decision, gathered by the analysis pipeline. */
data class TierInputs(
    val sqiScore: Double,
    val motionRejected: Boolean,
    val leadOffDetected: Boolean,
    val dataGapDetected: Boolean,
    val rrIntervalCount: Int,
    val meanHr: Double,
    val rrIrregularityScore: Double,
    /** INT8 CNN output, or null if the model did not run. */
    val cnnScore: Double? = null,
    val sqiFailureHint: String? = null,
    val pulseDeficitBpm: Double? = null,
    val perfusedBeatFraction: Double? = null,
    val fusionImplausible: Boolean = false,
    val historyIntermittent: Boolean = false,
    val historyPersistent: Boolean = false,
)

/**
 * Named constants `Explainer` reads directly. NOT the full `Policy` object -
 * see this file's header comment.
 */
object PolicyGates {
    const val RR_IRREGULARITY_GATE = 0.5
    const val HR_LOW = 50.0
    const val HR_HIGH = 120.0

    /**
     * MEASURED 2026-08-29 (ticket 016, outside this session) against the
     * current seed-0 model. Null would mean "uncalibrated, do not use" in
     * the real Policy; since module 3 isn't ported yet, Explainer's own
     * `cnnScore != null && cnnScore >= threshold` check reads this directly.
     */
    const val CNN_THRESHOLD_INT8 = 0.1875
}
