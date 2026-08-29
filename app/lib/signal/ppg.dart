import 'dart:math' as math;
import 'dart:typed_data';

import 'filters.dart';
import 'rr_features.dart';

/// Result of analysing one contact-PPG capture (MAX30102, IR channel).
class PpgResult {
  /// Sample indices of detected systolic peaks.
  final List<int> peaks;

  /// Inter-beat intervals in milliseconds.
  final Float64List ibiMs;

  /// Pulse rate from the PPG, beats per minute.
  final double meanPulseRate;

  /// Irregularity of the pulse interval series, on the same 0-1 scale as the
  /// ECG's RR irregularity so the two are directly comparable.
  final double irregularityScore;

  /// AC/DC x 100. Below [PpgAnalyser.kPerfusionGate] the trace is untrustworthy.
  final double perfusionIndex;

  /// Normalised spread of perfusion index across 1 s sub-windows of the
  /// capture: (max - min) / mean. Zero for a steady contact, high when the
  /// pulsatile signal swings up and down within one capture — which is what
  /// happens when a finger shifts on the sensor mid-recording.
  ///
  /// Distinct from [perfusionIndex] itself: that catches a signal that is
  /// UNIFORMLY weak (cold finger, low LED current); this catches one that is
  /// unstable, which a uniformly weak signal is not. This is the PPG's
  /// contribution to inferred motion detection, now that the MPU-6050 that
  /// used to sense motion directly is no longer in the BOM. See
  /// contracts/ppg.md.
  final double perfusionStabilityRatio;

  /// Whether this capture may be used for anything at all.
  final bool usable;

  final String? failureReason;

  /// Full interval statistics, for the record and for debugging.
  final RrFeatures features;

  const PpgResult({
    required this.peaks,
    required this.ibiMs,
    required this.meanPulseRate,
    required this.irregularityScore,
    required this.perfusionIndex,
    this.perfusionStabilityRatio = 0.0,
    required this.usable,
    required this.features,
    this.failureReason,
  });

  static final PpgResult unusable = PpgResult(
    peaks: const [],
    ibiMs: Float64List(0),
    meanPulseRate: 0,
    irregularityScore: 0,
    perfusionIndex: 0,
    usable: false,
    features: RrFeatures.empty,
    failureReason: 'No usable pulse signal',
  );

  /// Coarse outcome for the pre-screen step and for the screening record.
  /// Matches the `ppgResult` enum in contracts/record.schema.json.
  String get prescreenOutcome {
    if (!usable) return 'unclear';
    if (features.count < PpgAnalyser.kMinBeats) return 'unclear';
    return irregularityScore >= PpgAnalyser.kIrregularityGate
        ? 'irregular'
        : 'regular';
  }

  Map<String, dynamic> toJson() => {
        'meanPulseRate': meanPulseRate,
        'irregularityScore': irregularityScore,
        'perfusionIndex': perfusionIndex,
        'perfusionStabilityRatio': perfusionStabilityRatio,
        'beatCount': features.count,
        'usable': usable,
        'prescreenOutcome': prescreenOutcome,
        'failureReason': failureReason,
      };
}

/// Contact PPG analysis for the MAX30102 IR channel.
///
/// Deliberately mirrors the ECG path — same filter machinery, same interval
/// statistics via [RrAnalyser] — so that pulse irregularity and RR irregularity
/// live on one scale and can be compared without a conversion factor anyone has
/// to remember.
///
/// See contracts/ppg.md.
class PpgAnalyser {
  final double fs;

  /// Perfusion index (%) below which the PPG is discarded. A cold finger, weak
  /// contact, or insufficient LED current all land here. Discarding is correct:
  /// the ECG decision stands on its own, and a bad PPG must never weaken it.
  static const double kPerfusionGate = 0.3;

  /// Minimum systolic peaks before the interval statistics mean anything.
  /// Lower than the ECG's 30 because the PPG pre-screen runs on a shorter
  /// capture — its job is to decide whether to spend an electrode, not to
  /// produce a tier.
  static const int kMinBeats = 12;

  /// Pulse rates outside this range are rejected as detection failures rather
  /// than reported as findings.
  static const double kMinIbiMs = 250; // 240 bpm
  static const double kMaxIbiMs = 2000; // 30 bpm

  /// Peaks within this distance of either end of the capture are discarded.
  ///
  /// Zero-phase filtering leaves small transients at both edges — the padding
  /// suppresses most of it, but a capture that begins or ends mid-pulse still
  /// produces a bump large enough to clear the threshold. Those are not beats.
  ///
  /// This is not cosmetic. A spurious pulse inflates the PPG rate, which drives
  /// pulseDeficitBpm NEGATIVE, which the fusion correctly reports as impossible
  /// and turns into a RETAKE — so an edge artefact would present to the worker
  /// as "beat detection unreliable" on a perfectly good capture. Losing at most
  /// one real beat at each end of a 30 s window costs nothing by comparison.
  static const double kEdgeGuardMs = 500;

  /// Same gate value as the ECG rules, on the same scale, by construction.
  static const double kIrregularityGate = 0.5;

  /// Sub-window length for [_perfusionStability]. Short enough that a finger
  /// shift shows up as a swing between windows, long enough to contain
  /// several pulses per window so a single-window perfusion index is not
  /// itself noise.
  static const double kMotionSubWindowSec = 1.0;

  static const RrAnalyser _intervals = RrAnalyser();

  PpgAnalyser({this.fs = 100});

  PpgResult analyse(Float64List rawIr) {
    if (rawIr.length < fs * 5) {
      return PpgResult.unusable;
    }

    // Perfusion index first: it is a ratio of pulsatile amplitude to the DC
    // level, so it must be measured before the highpass removes the DC.
    final perfusion = _perfusionIndex(rawIr);
    if (perfusion < kPerfusionGate) {
      return PpgResult(
        peaks: const [],
        ibiMs: Float64List(0),
        meanPulseRate: 0,
        irregularityScore: 0,
        perfusionIndex: perfusion,
        usable: false,
        features: RrFeatures.empty,
        failureReason:
            'Weak pulse signal - warm the finger, rest it gently on the sensor',
      );
    }

    final band = FilterChain.ppgBand(fs).filtfilt(rawIr);
    final peaks = _detectSystolicPeaks(band);

    if (peaks.length < 2) {
      return PpgResult(
        peaks: peaks,
        ibiMs: Float64List(0),
        meanPulseRate: 0,
        irregularityScore: 0,
        perfusionIndex: perfusion,
        usable: false,
        features: RrFeatures.empty,
        failureReason: 'Could not find a pulse - reposition the finger',
      );
    }

    final ibi = Float64List(peaks.length - 1);
    for (var i = 0; i < ibi.length; i++) {
      ibi[i] = (peaks[i + 1] - peaks[i]) * 1000.0 / fs;
    }

    // Reuse the ECG interval statistics verbatim. AF is irregular in the pulse
    // for exactly the reason it is irregular in the RR series, so the same
    // RMSSD / pNN50 / entropy combination applies unchanged.
    final feats = _intervals.analyse(ibi);

    return PpgResult(
      peaks: peaks,
      ibiMs: ibi,
      meanPulseRate: feats.meanHr,
      irregularityScore: feats.irregularityScore,
      perfusionIndex: perfusion,
      perfusionStabilityRatio: _perfusionStability(rawIr),
      usable: feats.count >= 2,
      features: feats,
    );
  }

  /// AC/DC x 100, the standard perfusion index.
  ///
  /// AC is taken as the 5th-to-95th percentile span of the pulsatile component
  /// rather than the raw peak-to-peak, so a single motion spike cannot
  /// manufacture a healthy-looking number.
  double _perfusionIndex(Float64List raw) {
    var dc = 0.0;
    for (final v in raw) {
      dc += v;
    }
    dc /= raw.length;
    if (dc.abs() < 1e-9) return 0.0;

    final ac = FilterChain.ppgBand(fs).filtfilt(raw);
    final sorted = Float64List.fromList(ac)..sort();
    final lo = sorted[(sorted.length * 0.05).floor()];
    final hi = sorted[(sorted.length * 0.95).floor()];

    return ((hi - lo).abs() / dc.abs()) * 100.0;
  }

  /// Spread of local AC amplitude across 1 s sub-windows, normalised by the
  /// capture's overall DC level.
  ///
  /// Zero for a uniformly steady contact, whether that contact is good or bad
  /// — a uniformly weak signal is [kPerfusionGate]'s job, not this one's. High
  /// only when the signal quality itself swings during the capture, which is
  /// the PPG-side signature of the finger moving.
  ///
  /// Filters the WHOLE capture once, then measures local spread on the
  /// already-filtered signal — NOT by recomputing [_perfusionIndex]
  /// independently on each short sub-window. A 0.5 Hz highpass needs on the
  /// order of seconds to settle; re-running it from scratch on an isolated
  /// 100-sample (1 s) slice produces filter-edge noise of the same order as
  /// the physiological signal being measured, which swamped this metric with
  /// spurious instability on a perfectly steady synthetic capture during
  /// validation (`ml/reference/validate_ppg.py`). Filtering once and then
  /// windowing the result is what makes the measurement mean what it says.
  double _perfusionStability(Float64List raw) {
    var dc = 0.0;
    for (final v in raw) {
      dc += v;
    }
    dc /= raw.length;
    if (dc.abs() < 1e-9) return 0.0;

    final ac = FilterChain.ppgBand(fs).filtfilt(raw);
    final window = (kMotionSubWindowSec * fs).round();
    final n = raw.length ~/ window;
    if (n < 2) return 0.0;

    final spreads = Float64List(n);
    for (var i = 0; i < n; i++) {
      // Copy before sorting, matching _perfusionIndex above: a sublistView
      // shares the underlying buffer, so sorting it in place would mutate ac
      // itself. Harmless today (disjoint ranges, ac unused after this loop),
      // but a copy is what keeps that true if this function is ever refactored.
      final seg = Float64List.fromList(
          ac.sublist(i * window, (i + 1) * window))
        ..sort();
      final lo = seg[(seg.length * 0.05).floor()];
      final hi = seg[(seg.length * 0.95).floor()];
      spreads[i] = (hi - lo).abs() / dc.abs() * 100.0;
    }

    var mean = 0.0;
    for (final s in spreads) {
      mean += s;
    }
    mean /= spreads.length;
    if (mean < 1e-9) return 0.0;

    var maxV = spreads.first, minV = spreads.first;
    for (final s in spreads) {
      if (s > maxV) maxV = s;
      if (s < minV) minV = s;
    }
    return (maxV - minV) / mean;
  }

  /// Systolic peak detection on the bandpassed signal.
  ///
  /// A PPG pulse is a broad, smooth hump rather than the sharp spike of a QRS,
  /// so Pan-Tompkins' derivative-and-square emphasis is the wrong tool. An
  /// adaptive amplitude threshold with a physiological refractory period suits
  /// the waveform shape better and has far fewer ways to go wrong.
  List<int> _detectSystolicPeaks(Float64List x) {
    final refractory = math.max(1, (kMinIbiMs / 1000.0 * fs).round());

    // Threshold from the signal's own distribution: robust to LED current and
    // to how hard the patient is pressing, neither of which we control.
    final sorted = Float64List.fromList(x)..sort();
    final median = sorted[sorted.length ~/ 2];
    final upper = sorted[(sorted.length * 0.75).floor()];
    final threshold = median + 0.5 * (upper - median);

    final guard = (kEdgeGuardMs / 1000.0 * fs).round();
    final peaks = <int>[];
    for (var i = 1; i < x.length - 1; i++) {
      if (i < guard || i >= x.length - guard) continue;
      if (x[i] <= threshold) continue;
      if (x[i] <= x[i - 1] || x[i] < x[i + 1]) continue;
      if (peaks.isNotEmpty && i - peaks.last < refractory) {
        if (x[i] > x[peaks.last]) peaks[peaks.length - 1] = i;
        continue;
      }
      peaks.add(i);
    }
    return peaks;
  }
}
