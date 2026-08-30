package com.arogyax.core

/**
 * The decision. Ported line-for-line from `app/lib/core/policy.dart` (ticket 019,
 * module 3) and pinned to `ml/reference/validate_policy.py` through
 * `app/test/fixtures/policy_golden_vectors.json`.
 *
 * Two things this file is careful about, both of them safety properties rather
 * than style:
 *
 * 1. **Gates run first and are absolute.** Nothing below them can turn a refused
 *    window into a tier. `contracts/tiers.md` §2 row 1.
 * 2. **The PPG can only escalate.** It is consulted after the ECG has already
 *    found irregularity, and it answers "how urgently", never "whether".
 *    `contracts/ppg.md` §7.
 *
 * The gate constants that [Explainer] already reads live in [PolicyGates] and are
 * referenced from here rather than copied, so there is still exactly one value of
 * each in the Kotlin tree.
 */
object Policy {

    // ---- Gates -------------------------------------------------------------

    /** Below this signal quality the window is not scored at all. */
    const val K_SQI_GATE = 0.5

    /** RR statistics are meaningless below this many intervals. Fixed. */
    const val K_MIN_RR_INTERVALS = 30

    /** @see PolicyGates.RR_IRREGULARITY_GATE */
    const val K_RR_IRREGULARITY_GATE = PolicyGates.RR_IRREGULARITY_GATE

    /** @see PolicyGates.HR_LOW */
    const val K_HR_LOW = PolicyGates.HR_LOW

    /** @see PolicyGates.HR_HIGH */
    const val K_HR_HIGH = PolicyGates.HR_HIGH

    /**
     * Refitted on INT8 scores, never inherited from FP32. Null would mean
     * "uncalibrated, do not use"; it is non-null because ticket 016 measured it.
     *
     * @see PolicyGates.CNN_THRESHOLD_INT8
     */
    val K_CNN_THRESHOLD_INT8: Double? = PolicyGates.CNN_THRESHOLD_INT8

    // ---- PPG corroboration (contracts/ppg.md section 7) --------------------
    //
    // PROVISIONAL. No paired ECG+PPG AF dataset exists in this build, so these
    // two are targets, not fitted results. Marked as such in contracts/tiers.md
    // section 4 - do not quote them as measured.

    /** Beats per minute the ECG counted that never reached the finger. */
    const val K_PULSE_DEFICIT_BPM = 10.0

    /** Below this fraction of beats reaching the finger, the rhythm is failing to perfuse. */
    const val K_PERFUSED_BEAT_FRACTION_LOW = 0.90

    // ---- Versions ----------------------------------------------------------
    //
    // The calibration is part of the model version, because the threshold is
    // part of the model.

    const val K_RULES_VERSION = "rules-1.0"
    const val K_CNN_VERSION = "af-cnn-int8-1.0+cal1"

    fun decide(i: TierInputs): TierDecision {
        // --- 1. Gates. Checked first, and they are absolute. -----------------
        if (i.leadOffDetected) {
            return TierDecision(
                tier = Tier.RETAKE,
                decidedBy = DecidedBy.GATE,
                retakeReason = RetakeReason.ELECTRODE_DETACHED,
                retakeHint = "Electrode detached - reattach and retake",
            )
        }
        if (i.dataGapDetected) {
            // A dropped BLE frame deletes 100 ms of signal and manufactures a
            // short RR interval out of nothing, which looks exactly like AF. A
            // radio glitch must never become a referral. contracts/ble.md §3.
            return TierDecision(
                tier = Tier.RETAKE,
                decidedBy = DecidedBy.GATE,
                retakeReason = RetakeReason.DROPPED_DATA,
                retakeHint = "Connection dropped during capture - retake",
            )
        }
        if (i.motionRejected) {
            return TierDecision(
                tier = Tier.RETAKE,
                decidedBy = DecidedBy.GATE,
                retakeReason = RetakeReason.PATIENT_MOVED,
                retakeHint = "Ask the patient to sit still, then retake",
            )
        }
        if (i.sqiScore < K_SQI_GATE) {
            return TierDecision(
                tier = Tier.RETAKE,
                decidedBy = DecidedBy.GATE,
                retakeReason = RetakeReason.POOR_SIGNAL_QUALITY,
                retakeHint = i.sqiFailureHint ?: "Signal unclear - reposition and retake",
            )
        }
        if (i.rrIntervalCount < K_MIN_RR_INTERVALS) {
            return TierDecision(
                tier = Tier.RETAKE,
                decidedBy = DecidedBy.GATE,
                retakeReason = RetakeReason.TOO_FEW_BEATS,
                retakeHint = "Not enough beats captured - record for longer",
            )
        }
        if (i.fusionImplausible) {
            // More pulses than heartbeats is impossible, so the R-peak detector
            // missed beats. Missed beats fabricate long RR intervals, the same
            // corruption a dropped BLE frame causes. The PPG has caught an ECG
            // analysis failure the ECG could not detect on its own - a real
            // benefit of the second sensor, and one that must produce a retake
            // rather than a tier.
            return TierDecision(
                tier = Tier.RETAKE,
                decidedBy = DecidedBy.GATE,
                retakeReason = RetakeReason.BEAT_DETECTION_UNRELIABLE,
                retakeHint = "Beat detection unreliable - reposition and retake",
            )
        }

        // --- 2. Irregularity, from either detector. --------------------------
        val rulesFlag = i.rrIrregularityScore >= K_RR_IRREGULARITY_GATE

        // Both must be present for the CNN path to count: a score, and a
        // threshold that was actually refitted on INT8.
        val threshold = K_CNN_THRESHOLD_INT8
        val cnnScore = i.cnnScore
        val cnnRan: Boolean
        val cnnFlag: Boolean
        if (threshold != null && cnnScore != null) {
            cnnRan = true
            cnnFlag = cnnScore >= threshold
        } else {
            cnnRan = false
            cnnFlag = false
        }

        // OR, not average. This is a screening instrument and the cost asymmetry
        // is severe: a missed AF is a preventable stroke that happens, while a
        // false positive is one unnecessary PHC visit a clinician resolves in
        // minutes. We bias toward sensitivity, deliberately, and say so.
        val irregular = rulesFlag || cnnFlag

        val by: DecidedBy = when {
            !cnnRan -> DecidedBy.RULES
            rulesFlag && cnnFlag -> DecidedBy.RULES_AND_CNN
            cnnFlag -> DecidedBy.CNN
            else -> DecidedBy.RULES
        }

        if (!irregular) {
            // The PPG deliberately gets no say here. A screening instrument
            // biased toward sensitivity must not acquire a new way to reassure,
            // and a "normal" pulse must never clear a patient the ECG did not
            // clear. Every PPG path escalates, corroborates, or is discarded.
            //
            // History gets a narrow exception: an intermittent pattern is, by
            // definition, flagged on some visits and clean on others, so a clean
            // window from a patient with that history is not the same evidence
            // as a clean window from a patient with none (contracts/tiers.md §2,
            // "Why ORANGE can fire on a clean visit"). isPersistent does not get
            // this exception - a clean visit after an all-flagged history reads
            // as a real result, not as evidence of a hidden episode.
            if (i.historyIntermittent) {
                return TierDecision(tier = Tier.ORANGE, decidedBy = DecidedBy.HISTORY)
            }
            return TierDecision(tier = Tier.GREEN, decidedBy = by)
        }

        // --- 3. Rate decides how urgently, not whether. ----------------------
        // Irregularity with a controlled ventricular rate is a referral;
        // irregularity at 140 bpm is a referral that should not wait for market
        // day.
        val rateAbnormal = i.meanHr < K_HR_LOW || i.meanHr > K_HR_HIGH

        // --- 4. Contact PPG corroboration (contracts/ppg.md section 7). ------
        // Only consulted once the ECG has already found an irregular rhythm. The
        // mechanical evidence answers "how badly is this rhythm failing to move
        // blood", which is a question about urgency, not about presence.
        val ppg = corroboration(i)
        val corroborated = ppg == PpgCorroboration.PULSE_DEFICIT ||
            ppg == PpgCorroboration.NON_PERFUSING_BEATS

        // Beats that fail to reach the finger mean the irregularity is costing
        // this patient cardiac output right now. That earns the same urgency an
        // abnormal rate does. History never reaches RED on its own - only this
        // visit's own rate or mechanical evidence does.
        if (rateAbnormal || corroborated) {
            return TierDecision(tier = Tier.RED, decidedBy = by, ppg = ppg)
        }

        // Irregular, rate normal, nothing mechanically corroborating it: ORANGE
        // if this fits a pattern already seen across visits, YELLOW if it is the
        // first time (or there isn't enough history to call it a pattern).
        val repeatedAcrossVisits = i.historyIntermittent || i.historyPersistent
        val tier = if (repeatedAcrossVisits) Tier.ORANGE else Tier.YELLOW
        return TierDecision(tier = tier, decidedBy = by, ppg = ppg)
    }

    private fun corroboration(i: TierInputs): PpgCorroboration {
        val deficit = i.pulseDeficitBpm
        val perfused = i.perfusedBeatFraction
        if (deficit == null || perfused == null) return PpgCorroboration.NONE

        if (deficit >= K_PULSE_DEFICIT_BPM) return PpgCorroboration.PULSE_DEFICIT
        if (perfused < K_PERFUSED_BEAT_FRACTION_LOW) {
            return PpgCorroboration.NON_PERFUSING_BEATS
        }
        return PpgCorroboration.AGREED
    }

    /** The model version string to record, given which detectors actually ran. */
    fun versionFor(by: DecidedBy): String = when (by) {
        DecidedBy.GATE -> K_RULES_VERSION
        DecidedBy.RULES -> K_RULES_VERSION
        // No CNN contributed to a history-driven ORANGE: this visit's own rules
        // (and CNN, if it ran) said clean.
        DecidedBy.HISTORY -> K_RULES_VERSION
        DecidedBy.CNN, DecidedBy.RULES_AND_CNN -> "$K_RULES_VERSION+$K_CNN_VERSION"
    }
}
