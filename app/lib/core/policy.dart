/// The decision policy. Mirrors contracts/tiers.md exactly.
///
/// Every threshold in the product lives in this file and nowhere else, so
/// there is one place to tune them and no way for the running demo to drift
/// away from the contract document.
library;

/// Referral urgency. NOT a diagnosis.
///
/// See contracts/tiers.md section 1: the strings "atrial fibrillation", "AF",
/// and "arrhythmia" must never reach the worker-facing UI. A health worker who
/// tells a patient they have a heart condition has made a diagnosis on our
/// behalf, in a home, with no clinician present. The type system helps here —
/// this enum carries urgency, and there is deliberately no field anywhere on
/// it that could hold a condition name.
enum Tier {
  /// Not a result. Reposition and capture again.
  retake,

  /// Refer today, within 4 hours.
  red,

  /// Refer within 24 hours. Irregularity flagged (this visit, or a clean
  /// visit read against an intermittent history) and it fits a pattern
  /// already seen across this patient's visits — see contracts/tiers.md §2.
  orange,

  /// Refer within 48 hours. Irregularity flagged for the first time, with no
  /// repeated-visit pattern behind it (or not enough history to say).
  yellow,

  /// No rhythm concern today.
  green,
}

/// Which path produced the tier. Serialised into the record so the PHC can see
/// what evidence a referral rests on. [history] is the one case where neither
/// detector flagged this visit — a clean window escalated to
/// [Tier.orange] purely because [TierInputs.historyIntermittent] was true.
enum DecidedBy { gate, rules, cnn, rulesAndCnn, history }

/// Whether the contact PPG corroborated the ECG's finding. Recorded so the
/// PHC can see what evidence a referral rests on, and so the escalation from
/// YELLOW or ORANGE to RED is auditable rather than mysterious.
enum PpgCorroboration { none, unusable, agreed, pulseDeficit, nonPerfusingBeats }

/// Why a window was rejected, when [Tier.retake].
enum RetakeReason {
  poorSignalQuality,
  patientMoved,
  electrodeDetached,
  droppedData,
  tooFewBeats,

  /// PPG saw more pulses than the ECG saw beats, so the R-peak detector is
  /// wrong. Only reachable when a PPG was captured alongside the ECG.
  beatDetectionUnreliable,
}

class TierDecision {
  final Tier tier;
  final DecidedBy decidedBy;
  final RetakeReason? retakeReason;

  /// Populated only when [tier] is [Tier.retake]; a short hint for the worker
  /// about what to physically fix.
  final String? retakeHint;

  /// What the contact PPG contributed, if anything.
  final PpgCorroboration ppg;

  const TierDecision({
    required this.tier,
    required this.decidedBy,
    this.retakeReason,
    this.retakeHint,
    this.ppg = PpgCorroboration.none,
  });
}

/// Inputs to the decision, gathered by the analysis pipeline.
class TierInputs {
  final double sqiScore;
  final bool motionRejected;
  final bool leadOffDetected;
  final bool dataGapDetected;
  final int rrIntervalCount;
  final double meanHr;
  final double rrIrregularityScore;

  /// INT8 CNN output, or null if the model did not run.
  final double? cnnScore;

  final String? sqiFailureHint;

  // ---- Contact PPG (MAX30102). All null when no PPG was captured. ----------

  /// HR from the ECG minus pulse rate from the PPG, over the same window.
  /// Only meaningful when both were captured simultaneously on the shared
  /// ESP32 clock. See contracts/ppg.md §6.
  final double? pulseDeficitBpm;

  /// Fraction of R peaks that produced a peripheral pulse.
  final double? perfusedBeatFraction;

  /// True when the deficit came out negative — physiologically impossible, so
  /// the R-peak detector is at fault rather than the patient.
  final bool fusionImplausible;

  // ---- Patient history (contracts/tiers.md section 2) ----------------------
  //
  // Policy.decide never depends on PatientHistory directly: patient_history.dart
  // imports record.dart, which imports this file, so a PatientHistory field
  // here would be an import cycle. It would also break EcgAnalyser.analyse's
  // purity — that function answers only from the samples it was given, with
  // no store to query. Instead, the caller who already has both a fresh
  // analysis and this patient's history (a future capture-flow screen; not
  // built yet, see ticket 010) computes these two booleans and passes them
  // in, the same way it already assembles every other TierInputs field.

  /// PatientHistory.isIntermittent, computed from this patient's *prior*
  /// scored visits — never including the visit being decided right now.
  /// Defaults to false, matching today's single-visit behaviour when no
  /// history is available.
  final bool historyIntermittent;

  /// PatientHistory.isPersistent, same caller-computed contract as
  /// [historyIntermittent].
  final bool historyPersistent;

  const TierInputs({
    required this.sqiScore,
    required this.motionRejected,
    required this.leadOffDetected,
    required this.dataGapDetected,
    required this.rrIntervalCount,
    required this.meanHr,
    required this.rrIrregularityScore,
    this.cnnScore,
    this.sqiFailureHint,
    this.pulseDeficitBpm,
    this.perfusedBeatFraction,
    this.fusionImplausible = false,
    this.historyIntermittent = false,
    this.historyPersistent = false,
  });
}

class Policy {
  // ---- Gates -------------------------------------------------------------

  /// Below this signal quality the window is not scored at all.
  static const double kSqiGate = 0.5;

  /// RR statistics are meaningless below this many intervals. Fixed.
  static const int kMinRrIntervals = 30;

  // ---- Inferred motion (contracts/ble.md, contracts/ppg.md) ---------------
  //
  // The MPU-6050 that used to sense motion directly is no longer in the BOM.
  // Motion is inferred instead from two signals already computed for other
  // reasons, OR'd together the same way the two AF detectors are: movement
  // mimics atrial fibrillation, so a screening tool has to bias toward
  // catching it even at the cost of an occasional unnecessary retake.
  //
  // Both thresholds are PROVISIONAL — physiologically reasoned, not fitted.
  // There is no labelled disturbed-vs-still capture pairing in this build to
  // fit them on (that is ticket 003's hardware bring-up); per CLAUDE.md
  // non-negotiable 8 these are targets, not results, until retuned against
  // real captures where the patient was deliberately moved.

  /// ECG baseline-wander ratio (sqi.dart) at or above this counts as inferred
  /// motion. Wander is caused by breathing, electrode movement, and cable
  /// sway — motion is a subset of its causes, not the whole of them, which is
  /// the known cost of inference: an IMU could tell "the patient moved" apart
  /// from "the electrode is loose", and this cannot. Set below the
  /// catastrophic-SQI-failure threshold (0.80) so real movement is caught
  /// before the whole capture's quality collapses, not only after.
  static const double kMotionWanderRatioGate = 0.35;

  /// PPG perfusion-index instability (ppg.dart) at or above this corroborates
  /// inferred motion. Zero for a steady contact; high when the pulsatile
  /// signal swings within one capture, which is the PPG-side signature of a
  /// finger shifting on the sensor.
  static const double kMotionPerfusionInstabilityGate = 1.0;

  // ---- Irregularity ------------------------------------------------------

  /// Rule-based irregularity at or above this counts as "irregularity high".
  /// Tuned against MIT-BIH AFDB.
  static const double kRrIrregularityGate = 0.5;

  /// CNN threshold, REFITTED ON INT8 SCORES.
  ///
  /// This constant is the product's technical differentiator
  /// (docs/PRODUCT.md section 6). It must never be inherited from the FP32
  /// model: quantisation shifts the score distribution, so an FP32-derived
  /// threshold silently costs sensitivity on the deployed model — and lost
  /// sensitivity in an AF screen means missed AF, which means strokes that
  /// were preventable and were not prevented.
  ///
  /// Filled in from ml/calibrate_threshold.py and nowhere else. Null until that
  /// has been run, and while it is null the CNN path is simply not used — we do
  /// not fall back to a guess.
  ///
  /// MEASURED 2026-08-28, seed 0, CinC 2017 record-disjoint test split
  /// (1364 windows, 124 AF). Target sensitivity 0.90:
  ///
  ///   FP32 threshold carried over naively -> Se 0.895, Sp 0.830
  ///   refitted on INT8 scores             -> Se 0.919, Sp 0.810
  ///
  /// Across 5 seeds the sensitivity lost by carrying the FP32 threshold over was
  /// +0.008 mean, sd 0.005, range [0.000, 0.016]. The variance is the point: the
  /// shift cannot be predicted in advance, so it has to be measured per build.
  ///
  /// Note the refit lands on 0.919 rather than 0.903. That is not sloppiness —
  /// the INT8 output is a single int8, so the whole test set falls on 57–88
  /// distinct scores in steps of 1/256, and only 11–21 operating points exist
  /// anywhere in Se ∈ [0.80, 0.98]. There is no threshold that yields 0.90.
  /// Quantisation does not merely shift the operating point, it collapses which
  /// operating points are reachable at all.
  static const double? kCnnThresholdInt8 = 0.007812;

  // ---- Rate --------------------------------------------------------------

  static const double kHrLow = 50;
  static const double kHrHigh = 120;

  // ---- Contact PPG fusion (contracts/ppg.md) ------------------------------

  /// Pulse deficit at or above this corroborates an irregular rhythm.
  ///
  /// PROVISIONAL — physiologically motivated, not fitted. There is no paired
  /// ECG+PPG AF dataset in this build to fit it on, so per docs/PRODUCT.md §10
  /// this is a target, not a result, and must be labelled as such if quoted.
  static const double kPulseDeficitBpm = 10;

  /// Below this fraction of R peaks producing a pulse, beats are failing to
  /// perfuse. Also PROVISIONAL.
  static const double kPerfusedBeatFractionLow = 0.90;

  // ---- Versioning --------------------------------------------------------

  /// The calibration is part of the model version, because the threshold is
  /// part of the model.
  static const String kRulesVersion = 'rules-1.0';
  static const String kCnnVersion = 'af-cnn-int8-1.0+cal1';

  const Policy._();

  static TierDecision decide(TierInputs i) {
    // --- 1. Gates. Checked first, and they are absolute. -------------------
    if (i.leadOffDetected) {
      return const TierDecision(
        tier: Tier.retake,
        decidedBy: DecidedBy.gate,
        retakeReason: RetakeReason.electrodeDetached,
        retakeHint: 'Electrode detached - reattach and retake',
      );
    }
    if (i.dataGapDetected) {
      // A dropped BLE frame deletes 100 ms of signal and manufactures a short
      // RR interval out of nothing, which looks exactly like AF. A radio
      // glitch must never become a referral. See contracts/ble.md section 3.
      return const TierDecision(
        tier: Tier.retake,
        decidedBy: DecidedBy.gate,
        retakeReason: RetakeReason.droppedData,
        retakeHint: 'Connection dropped during capture - retake',
      );
    }
    if (i.motionRejected) {
      return const TierDecision(
        tier: Tier.retake,
        decidedBy: DecidedBy.gate,
        retakeReason: RetakeReason.patientMoved,
        retakeHint: 'Ask the patient to sit still, then retake',
      );
    }
    if (i.sqiScore < kSqiGate) {
      return TierDecision(
        tier: Tier.retake,
        decidedBy: DecidedBy.gate,
        retakeReason: RetakeReason.poorSignalQuality,
        retakeHint: i.sqiFailureHint ?? 'Signal unclear - reposition and retake',
      );
    }
    if (i.rrIntervalCount < kMinRrIntervals) {
      return const TierDecision(
        tier: Tier.retake,
        decidedBy: DecidedBy.gate,
        retakeReason: RetakeReason.tooFewBeats,
        retakeHint: 'Not enough beats captured - record for longer',
      );
    }
    if (i.fusionImplausible) {
      // More pulses than heartbeats is impossible, so the R-peak detector
      // missed beats. Missed beats fabricate long RR intervals, which is the
      // same corruption a dropped BLE frame causes. The PPG has effectively
      // caught an ECG analysis failure the ECG could not detect on its own —
      // a real benefit of the second sensor, and one that must produce a
      // retake rather than a tier.
      return const TierDecision(
        tier: Tier.retake,
        decidedBy: DecidedBy.gate,
        retakeReason: RetakeReason.beatDetectionUnreliable,
        retakeHint: 'Beat detection unreliable - reposition and retake',
      );
    }

    // --- 2. Irregularity, from either detector. ---------------------------
    final rulesFlag = i.rrIrregularityScore >= kRrIrregularityGate;

    // Both must be present for the CNN path to count: a score, and a threshold
    // that was actually refitted on INT8. Written as an if/else rather than a
    // chained condition so null promotion applies to `threshold` inside the
    // comparison.
    final threshold = kCnnThresholdInt8;
    final cnnScore = i.cnnScore;
    final bool cnnRan;
    final bool cnnFlag;
    if (threshold != null && cnnScore != null) {
      cnnRan = true;
      cnnFlag = cnnScore >= threshold;
    } else {
      cnnRan = false;
      cnnFlag = false;
    }

    // OR, not average. This is a screening instrument and the cost asymmetry
    // is severe: a missed AF is a preventable stroke that happens, while a
    // false positive is one unnecessary PHC visit a clinician resolves in
    // minutes. We bias toward sensitivity, deliberately, and say so.
    final irregular = rulesFlag || cnnFlag;

    final DecidedBy by;
    if (!cnnRan) {
      by = DecidedBy.rules;
    } else if (rulesFlag && cnnFlag) {
      by = DecidedBy.rulesAndCnn;
    } else if (cnnFlag) {
      by = DecidedBy.cnn;
    } else {
      by = DecidedBy.rules;
    }

    if (!irregular) {
      // The PPG deliberately gets no say here. A screening instrument biased
      // toward sensitivity must not acquire a new way to reassure, and a
      // "normal" pulse must never be able to clear a patient the ECG did not
      // clear. Every PPG path escalates, corroborates, or is discarded.
      //
      // History gets a narrow exception: an intermittent pattern is, by
      // definition, flagged on some visits and clean on others, so a clean
      // window from a patient with that history is not the same evidence as
      // a clean window from a patient with none (contracts/tiers.md §2,
      // "Why ORANGE can fire on a clean visit"). isPersistent does not get
      // this exception — a clean visit after an all-flagged history reads as
      // a real result, not as evidence of a hidden episode.
      if (i.historyIntermittent) {
        return const TierDecision(tier: Tier.orange, decidedBy: DecidedBy.history);
      }
      return TierDecision(tier: Tier.green, decidedBy: by);
    }

    // --- 3. Rate decides how urgently, not whether. -----------------------
    // Irregularity with a controlled ventricular rate is a referral;
    // irregularity at 140 bpm is a referral that should not wait for market day.
    final rateAbnormal = i.meanHr < kHrLow || i.meanHr > kHrHigh;

    // --- 4. Contact PPG corroboration (contracts/ppg.md §7). ---------------
    // Only consulted once the ECG has already found an irregular rhythm. The
    // mechanical evidence answers "how badly is this rhythm failing to move
    // blood", which is a question about urgency, not about presence.
    final ppg = _corroboration(i);
    final corroborated = ppg == PpgCorroboration.pulseDeficit ||
        ppg == PpgCorroboration.nonPerfusingBeats;

    // Beats that fail to reach the finger mean the irregularity is costing
    // this patient cardiac output right now. That earns the same urgency an
    // abnormal rate does. History never reaches RED on its own — only this
    // visit's own rate or mechanical evidence does.
    if (rateAbnormal || corroborated) {
      return TierDecision(tier: Tier.red, decidedBy: by, ppg: ppg);
    }

    // Irregular, rate normal, nothing mechanically corroborating it: ORANGE
    // if this fits a pattern already seen across visits, YELLOW if it is the
    // first time (or there isn't enough history to call it a pattern).
    final repeatedAcrossVisits = i.historyIntermittent || i.historyPersistent;
    final tier = repeatedAcrossVisits ? Tier.orange : Tier.yellow;
    return TierDecision(tier: tier, decidedBy: by, ppg: ppg);
  }

  static PpgCorroboration _corroboration(TierInputs i) {
    final deficit = i.pulseDeficitBpm;
    final perfused = i.perfusedBeatFraction;
    if (deficit == null || perfused == null) return PpgCorroboration.none;

    if (deficit >= kPulseDeficitBpm) return PpgCorroboration.pulseDeficit;
    if (perfused < kPerfusedBeatFractionLow) {
      return PpgCorroboration.nonPerfusingBeats;
    }
    return PpgCorroboration.agreed;
  }

  /// The model version string to record, given which detectors actually ran.
  static String versionFor(DecidedBy by) => switch (by) {
        DecidedBy.gate => kRulesVersion,
        DecidedBy.rules => kRulesVersion,
        // No CNN contributed to a history-driven ORANGE: this visit's own
        // rules (and CNN, if it ran) said clean.
        DecidedBy.history => kRulesVersion,
        DecidedBy.cnn || DecidedBy.rulesAndCnn => '$kRulesVersion+$kCnnVersion',
      };
}
