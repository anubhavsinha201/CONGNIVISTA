import 'dart:typed_data';

import '../core/policy.dart';
import 'filters.dart';
import 'pan_tompkins.dart';
import 'ppg.dart';
import 'rr_features.dart';
import 'sqi.dart';

/// Everything the result screen and the screening record need from one capture.
class ScreeningAnalysis {
  final TierDecision decision;
  final SqiResult sqi;
  final RrFeatures rr;
  final List<int> rPeaks;

  /// Conditioned trace (0.5-40 Hz, mains notched), for the result screen and
  /// the referral card thumbnail.
  final Float64List conditioned;

  final double durationSec;
  final bool motionRejected;
  final bool leadOffDetected;
  final bool dataGapDetected;
  final double? cnnScore;

  const ScreeningAnalysis({
    required this.decision,
    required this.sqi,
    required this.rr,
    required this.rPeaks,
    required this.conditioned,
    required this.durationSec,
    required this.motionRejected,
    required this.leadOffDetected,
    required this.dataGapDetected,
    this.cnnScore,
  });

  /// The subset of contracts/record.schema.json this layer is responsible for.
  /// The data layer merges in identity, location, and sync fields.
  Map<String, dynamic> toRecordFields() => {
        'ecgDurationSec': durationSec,
        'sqiScore': sqi.score,
        'motionRejected': motionRejected,
        'leadOffDetected': leadOffDetected,
        'meanHr': rr.count > 0 ? rr.meanHr : null,
        'rrIntervalCount': rr.count,
        'rrIrregularityScore': rr.count > 0 ? rr.irregularityScore : null,
        'cnnScore': cnnScore,
        'decidedBy': switch (decision.decidedBy) {
          DecidedBy.gate => 'gate',
          DecidedBy.rules => 'rules',
          DecidedBy.cnn => 'cnn',
          DecidedBy.rulesAndCnn => 'rules+cnn',
        },
        'tier': decision.tier.name.toUpperCase(),
        'modelVersion': Policy.versionFor(decision.decidedBy),
      };
}

/// The single entry point from the capture screen to the decision.
///
/// Pure and synchronous: given the same samples it always returns the same
/// answer, which is what makes the whole pipeline testable against golden
/// vectors without a device, a Bluetooth stack, or a running app.
class EcgAnalyser {
  final double fs;
  late final PanTompkins _detector = PanTompkins(fs);
  late final SqiAnalyser _sqi = SqiAnalyser(fs);
  static const RrAnalyser _rr = RrAnalyser();

  EcgAnalyser({this.fs = 250});

  /// [rawAdu] is samples as delivered over BLE: ADC value minus 2048.
  ///
  /// [ppg] is the simultaneous contact-PPG capture, when there is one — it
  /// corroborates inferred motion (see [Policy.kMotionPerfusionInstabilityGate])
  /// but is entirely optional, since a plain ECG-only capture is a normal
  /// thing to have. [dataGapDetected] must be true if any BLE sequence number
  /// was skipped during the capture.
  ///
  /// [cnnScore] is supplied by the caller if the INT8 model ran. Null is a
  /// perfectly normal state — the rules path stands on its own.
  ScreeningAnalysis analyse(
    Float64List rawAdu, {
    PpgResult? ppg,
    bool leadOffDetected = false,
    bool dataGapDetected = false,
    double? cnnScore,
  }) {
    final durationSec = rawAdu.length / fs;

    // Quality is assessed on the RAW trace, before any filtering. Filtering a
    // detached-electrode signal produces something that looks reassuringly
    // like a flat baseline; the gate has to see what actually arrived.
    final sqi = _sqi.analyse(rawAdu);

    // Motion is INFERRED, not sensed — the MPU-6050 that used to answer this
    // directly is no longer in the BOM. OR'd across both available signals,
    // the same sensitivity-biased pattern the two AF detectors use: the ECG's
    // own baseline wander is always available, and the PPG's perfusion
    // stability corroborates it whenever a simultaneous capture exists. See
    // Policy's doc comments for why each threshold is provisional.
    final motionRejected = sqi.baselineWanderRatio >= Policy.kMotionWanderRatioGate ||
        (ppg != null &&
            ppg.usable &&
            ppg.perfusionStabilityRatio >= Policy.kMotionPerfusionInstabilityGate);

    final conditioned = FilterChain.ecgConditioning(fs).filtfilt(rawAdu);

    // Short-circuit before interpreting anything, so an ungated window can
    // never produce a heart rate that some later screen might display.
    final gated = leadOffDetected ||
        dataGapDetected ||
        motionRejected ||
        sqi.score < Policy.kSqiGate;

    if (gated) {
      final decision = Policy.decide(TierInputs(
        sqiScore: sqi.score,
        motionRejected: motionRejected,
        leadOffDetected: leadOffDetected,
        dataGapDetected: dataGapDetected,
        rrIntervalCount: 0,
        meanHr: 0,
        rrIrregularityScore: 0,
        sqiFailureHint: sqi.failureReason,
      ));
      return ScreeningAnalysis(
        decision: decision,
        sqi: sqi,
        rr: RrFeatures.empty,
        rPeaks: const [],
        conditioned: conditioned,
        durationSec: durationSec,
        motionRejected: motionRejected,
        leadOffDetected: leadOffDetected,
        dataGapDetected: dataGapDetected,
      );
    }

    final peaks = _detector.detect(rawAdu);
    final rr = _rr.analyse(peaks.rrIntervalsMs(fs));

    final decision = Policy.decide(TierInputs(
      sqiScore: sqi.score,
      motionRejected: motionRejected,
      leadOffDetected: leadOffDetected,
      dataGapDetected: dataGapDetected,
      rrIntervalCount: rr.count,
      meanHr: rr.meanHr,
      rrIrregularityScore: rr.irregularityScore,
      cnnScore: cnnScore,
      sqiFailureHint: sqi.failureReason,
    ));

    return ScreeningAnalysis(
      decision: decision,
      sqi: sqi,
      rr: rr,
      rPeaks: peaks.peaks,
      conditioned: conditioned,
      durationSec: durationSec,
      motionRejected: motionRejected,
      leadOffDetected: leadOffDetected,
      dataGapDetected: dataGapDetected,
      cnnScore: cnnScore,
    );
  }
}
