import 'dart:math' as math;
import 'dart:typed_data';

/// Statistics over a series of RR intervals, plus the combined irregularity
/// score the tier policy consumes.
class RrFeatures {
  final int count;
  final double meanRrMs;
  final double meanHr;
  final double rmssdMs;

  /// RMSSD normalised by mean RR. Dimensionless, so it does not drift with
  /// heart rate the way raw RMSSD does.
  final double normalisedRmssd;

  /// Fraction of successive intervals differing by more than 50 ms.
  final double pnn50;

  /// Shannon entropy of the RR histogram, normalised to 0-1.
  final double normalisedShannonEntropy;

  /// Combined 0-1 irregularity score. Consumed by the tier policy.
  final double irregularityScore;

  /// Intervals discarded as physiologically impossible.
  final int rejectedIntervals;

  const RrFeatures({
    required this.count,
    required this.meanRrMs,
    required this.meanHr,
    required this.rmssdMs,
    required this.normalisedRmssd,
    required this.pnn50,
    required this.normalisedShannonEntropy,
    required this.irregularityScore,
    required this.rejectedIntervals,
  });

  static const RrFeatures empty = RrFeatures(
    count: 0,
    meanRrMs: 0,
    meanHr: 0,
    rmssdMs: 0,
    normalisedRmssd: 0,
    pnn50: 0,
    normalisedShannonEntropy: 0,
    irregularityScore: 0,
    rejectedIntervals: 0,
  );

  Map<String, dynamic> toJson() => {
        'count': count,
        'meanRrMs': meanRrMs,
        'meanHr': meanHr,
        'rmssdMs': rmssdMs,
        'normalisedRmssd': normalisedRmssd,
        'pnn50': pnn50,
        'normalisedShannonEntropy': normalisedShannonEntropy,
        'irregularityScore': irregularityScore,
        'rejectedIntervals': rejectedIntervals,
      };
}

/// Extracts the RR-interval statistics that discriminate atrial fibrillation.
///
/// The three measures are the ones repeatedly validated in the AF-screening
/// literature (Dash et al. 2009; Lian et al. 2011): normalised RMSSD, pNN50,
/// and the normalised Shannon entropy of the RR histogram. They capture
/// complementary aspects of "irregularly irregular" — respectively the size of
/// beat-to-beat changes, how often large changes occur, and how spread out the
/// interval distribution is overall.
///
/// This is not a fallback for the CNN. Irregularly-irregular RR timing is the
/// clinical signature of AF, and unlike a neural network it can be explained,
/// in full, to a clinician who asks why a patient was referred.
class RrAnalyser {
  /// Physiological plausibility bounds: 30-200 bpm.
  static const double minPlausibleRrMs = 300;
  static const double maxPlausibleRrMs = 2000;

  static const int histogramBins = 16;

  /// Logistic centres and widths for combining the three measures.
  ///
  /// PROVISIONAL. These are literature-derived starting points and MUST be
  /// retuned against MIT-BIH AFDB before any performance claim is made
  /// (docs/PRODUCT.md section 10: anything not measured is a target, not a
  /// result). See ml/reference/tune_rr_thresholds.py.
  static const double nRmssdCentre = 0.08, nRmssdWidth = 0.02;
  static const double pnn50Centre = 0.30, pnn50Width = 0.08;
  static const double entropyCentre = 0.65, entropyWidth = 0.06;

  static const double wRmssd = 0.4, wPnn50 = 0.3, wEntropy = 0.3;

  const RrAnalyser();

  RrFeatures analyse(Float64List rrMs) {
    final clean = <double>[];
    var rejected = 0;
    for (final rr in rrMs) {
      if (rr >= minPlausibleRrMs && rr <= maxPlausibleRrMs) {
        clean.add(rr);
      } else {
        rejected++;
      }
    }

    // NOTE: we filter only physiologically impossible intervals. The usual
    // ectopic-beat filter — drop any interval deviating more than ~20% from
    // the running median — is deliberately NOT applied here. In atrial
    // fibrillation, large deviations from the median are not artefact; they
    // are the finding. Applying that filter would smooth away precisely the
    // signal this analyser exists to detect.

    if (clean.length < 2) {
      return RrFeatures.empty;
    }

    final n = clean.length;
    final meanRr = clean.reduce((a, b) => a + b) / n;

    var sumSqDiff = 0.0;
    var over50 = 0;
    for (var i = 1; i < n; i++) {
      final d = clean[i] - clean[i - 1];
      sumSqDiff += d * d;
      if (d.abs() > 50) over50++;
    }
    final rmssd = math.sqrt(sumSqDiff / (n - 1));
    final pnn50 = over50 / (n - 1);
    final nRmssd = meanRr > 0 ? rmssd / meanRr : 0.0;
    final entropy = _normalisedShannonEntropy(clean);

    final score = (wRmssd * _logistic(nRmssd, nRmssdCentre, nRmssdWidth) +
            wPnn50 * _logistic(pnn50, pnn50Centre, pnn50Width) +
            wEntropy * _logistic(entropy, entropyCentre, entropyWidth))
        .clamp(0.0, 1.0);

    return RrFeatures(
      count: n,
      meanRrMs: meanRr,
      meanHr: meanRr > 0 ? 60000.0 / meanRr : 0.0,
      rmssdMs: rmssd,
      normalisedRmssd: nRmssd,
      pnn50: pnn50,
      normalisedShannonEntropy: entropy,
      irregularityScore: score,
      rejectedIntervals: rejected,
    );
  }

  /// Shannon entropy of a 16-bin RR histogram, normalised by ln(16) so the
  /// result is 0 (all intervals identical) to 1 (uniformly spread).
  ///
  /// The histogram range is taken after trimming the most extreme 5% at each
  /// end, so a single outlier cannot stretch the bin width and artificially
  /// collapse the distribution into one bin.
  double _normalisedShannonEntropy(List<double> rr) {
    final sorted = List<double>.from(rr)..sort();
    final trim = (sorted.length * 0.05).floor();
    final lo = sorted[trim];
    final hi = sorted[sorted.length - 1 - trim];
    final range = hi - lo;
    if (range <= 0) return 0.0;

    final counts = List<int>.filled(histogramBins, 0);
    var total = 0;
    for (final v in rr) {
      if (v < lo || v > hi) continue;
      var b = ((v - lo) / range * histogramBins).floor();
      if (b >= histogramBins) b = histogramBins - 1;
      counts[b]++;
      total++;
    }
    if (total == 0) return 0.0;

    var h = 0.0;
    for (final c in counts) {
      if (c == 0) continue;
      final p = c / total;
      h -= p * math.log(p);
    }
    return (h / math.log(histogramBins.toDouble())).clamp(0.0, 1.0);
  }

  /// Soft threshold. A hard cut-off makes the score jump between two patients
  /// whose intervals differ by a millisecond; a logistic keeps the output
  /// continuous, which is also what lets it be compared against the CNN score
  /// on the same 0-1 scale.
  double _logistic(double x, double centre, double width) =>
      1.0 / (1.0 + math.exp(-(x - centre) / width));
}
