import 'dart:math' as math;

/// Quantities that require ECG and PPG captured together on a shared clock.
///
/// These are the reason the second sensor exists. Everything else the PPG
/// provides is a correlated second opinion on a rhythm the ECG already sees;
/// these are things neither sensor can produce alone. See contracts/ppg.md §6.
class FusionFeatures {
  /// HR from the ECG minus pulse rate from the PPG, over the same window.
  ///
  /// In atrial fibrillation a beat arriving too soon leaves the ventricle
  /// underfilled, so its stroke volume is too small to reach the finger. The
  /// heart contracts and the periphery feels nothing, making the electrical
  /// rate exceed the mechanical one. This is the classic pulse deficit.
  final double pulseDeficitBpm;

  /// Fraction of R peaks followed by a pulse within the transit-time window.
  ///
  /// Finer-grained than the rate difference: it identifies *which* beats failed
  /// to perfuse rather than only how many.
  final double perfusedBeatFraction;

  /// R peaks that had no matching pulse.
  final int nonPerfusingBeats;

  /// Median pulse transit time (ms) across matched beats. Reported for the
  /// record; not currently used in the decision.
  final double medianPttMs;

  /// False when the two captures were not simultaneous, or either side was
  /// unusable. When false every field above is meaningless and must be ignored.
  final bool valid;

  /// Set when the deficit came out negative, which is physiologically
  /// impossible and indicates the R-peak detector, not the patient.
  final bool implausible;

  final String? invalidReason;

  const FusionFeatures({
    required this.pulseDeficitBpm,
    required this.perfusedBeatFraction,
    required this.nonPerfusingBeats,
    required this.medianPttMs,
    required this.valid,
    this.implausible = false,
    this.invalidReason,
  });

  static const FusionFeatures none = FusionFeatures(
    pulseDeficitBpm: 0,
    perfusedBeatFraction: 0,
    nonPerfusingBeats: 0,
    medianPttMs: 0,
    valid: false,
    invalidReason: 'ECG and PPG not captured together',
  );

  Map<String, dynamic> toJson() => {
        'pulseDeficitBpm': valid ? pulseDeficitBpm : null,
        'perfusedBeatFraction': valid ? perfusedBeatFraction : null,
        'nonPerfusingBeats': valid ? nonPerfusingBeats : null,
        'medianPttMs': valid ? medianPttMs : null,
        'fusionValid': valid,
        'fusionImplausible': implausible,
      };
}

class FusionAnalyser {
  /// Pulse transit time window, measured **R peak to PPG systolic peak** — not
  /// to the pulse foot. The foot arrives ~150-300 ms after the R peak and the
  /// systolic peak a further ~140 ms later, so matching against peaks needs the
  /// wider bound. Getting this wrong does not throw: it silently reports beats
  /// as non-perfusing, which reads as a clinical finding rather than a bug.
  static const double kPttMinMs = 150;
  static const double kPttMaxMs = 450;

  /// The two captures must overlap by at least this long for a rate comparison
  /// to mean anything.
  static const double kMinOverlapSec = 10;

  const FusionAnalyser._();

  /// [ecgPeakTimesMs] and [ppgPeakTimesMs] must both be on the ESP32's single
  /// `millis()` timebase (contracts/ppg.md §4).
  ///
  /// Passing phone arrival times here would be a silent disaster: BLE buffering
  /// jitter is tens of milliseconds, the same order as the transit time being
  /// measured, so every output would be noise dressed as a clinical finding.
  static FusionFeatures analyse({
    required List<double> ecgPeakTimesMs,
    required List<double> ppgPeakTimesMs,
    required bool ppgUsable,
    required bool simultaneous,
  }) {
    if (!simultaneous) return FusionFeatures.none;
    if (!ppgUsable) {
      return const FusionFeatures(
        pulseDeficitBpm: 0,
        perfusedBeatFraction: 0,
        nonPerfusingBeats: 0,
        medianPttMs: 0,
        valid: false,
        invalidReason: 'PPG below the perfusion gate',
      );
    }
    if (ecgPeakTimesMs.length < 2 || ppgPeakTimesMs.length < 2) {
      return const FusionFeatures(
        pulseDeficitBpm: 0,
        perfusedBeatFraction: 0,
        nonPerfusingBeats: 0,
        medianPttMs: 0,
        valid: false,
        invalidReason: 'Too few beats to compare',
      );
    }

    // Work on the ECG timeline, bounded to the beats whose pulse could actually
    // have been observed.
    //
    // An earlier version took the overlap as [max(first), min(last)] across both
    // streams. That is wrong, and wrong in a way that produced a plausible
    // number rather than an error: the two event types are separated by the
    // transit time, so a window shared between them contains one more pulse than
    // it does beats. On a healthy volunteer that yielded a NEGATIVE deficit,
    // which this class correctly rejects as impossible — meaning the bug
    // surfaced as a retake on a perfectly good capture.
    final start = math.max(ecgPeakTimesMs.first, ppgPeakTimesMs.first - kPttMaxMs);
    final end = math.min(ecgPeakTimesMs.last, ppgPeakTimesMs.last - kPttMinMs);
    final overlapSec = (end - start) / 1000.0;
    if (overlapSec < kMinOverlapSec) {
      return FusionFeatures(
        pulseDeficitBpm: 0,
        perfusedBeatFraction: 0,
        nonPerfusingBeats: 0,
        medianPttMs: 0,
        valid: false,
        invalidReason:
            'Captures overlap by only ${overlapSec.toStringAsFixed(1)} s',
      );
    }

    final ecgIn = ecgPeakTimesMs.where((t) => t >= start && t <= end).toList();
    final ppgIn = ppgPeakTimesMs
        .where((t) => t >= start + kPttMinMs && t <= end + kPttMaxMs)
        .toList();
    if (ecgIn.length < 2) return FusionFeatures.none;

    // Match each R peak to the first pulse inside the transit window.
    // Greedy and forward-only: a pulse belongs to at most one beat, and pulses
    // cannot arrive out of order.
    var matched = 0;
    var ppgIdx = 0;
    final ptts = <double>[];
    for (final r in ecgIn) {
      while (ppgIdx < ppgIn.length && ppgIn[ppgIdx] < r + kPttMinMs) {
        ppgIdx++;
      }
      if (ppgIdx < ppgIn.length && ppgIn[ppgIdx] <= r + kPttMaxMs) {
        ptts.add(ppgIn[ppgIdx] - r);
        matched++;
        ppgIdx++;
      }
    }

    final fraction = matched / ecgIn.length;
    ptts.sort();
    final medianPtt = ptts.isEmpty ? 0.0 : ptts[ptts.length ~/ 2];

    // Pulse deficit derived from the per-beat matching rather than from a
    // difference of two independently-windowed rates.
    //
    // This is deliberately NOT an independent second measurement — it is the
    // same finding expressed as a rate, which is how the sign is read at the
    // bedside ("apex rate minus radial rate"). Deriving it removes the
    // windowing bias entirely and makes a negative value structurally
    // impossible. The policy may therefore treat the two as one piece of
    // evidence, which is what they are; do not present them as corroborating
    // each other.
    final hrEcg = (ecgIn.length - 1) / overlapSec * 60.0;
    final deficit = hrEcg * (1.0 - fraction);

    // The genuine detector-failure signal: more pulses observed than beats.
    // A finger cannot pulse without a heartbeat, so this means the R-peak
    // detector missed beats — which fabricates long RR intervals and would
    // otherwise be scored as an irregular rhythm.
    final implausible = ppgIn.length > ecgIn.length * 1.15;

    return FusionFeatures(
      pulseDeficitBpm: deficit,
      perfusedBeatFraction: fraction,
      nonPerfusingBeats: ecgIn.length - matched,
      medianPttMs: medianPtt,
      valid: !implausible,
      implausible: implausible,
      invalidReason: implausible
          ? 'More pulses than heartbeats - R-peak detection is wrong'
          : null,
    );
  }
}
