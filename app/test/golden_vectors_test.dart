import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

import 'package:arogyax/signal/pan_tompkins.dart';
import 'package:arogyax/signal/rr_features.dart';
import 'package:arogyax/signal/sqi.dart';

/// Pins the Dart DSP to the verified Python reference implementation.
///
/// The vectors in test/fixtures/golden_vectors.json are produced by
/// ml/reference/validate_dsp.py, whose filter design is checked against scipy
/// and whose R-peak detection is measured against synthetic signals with exact
/// known peak positions. If these tests fail, the two implementations have
/// drifted apart, and the Python side is the one that has been independently
/// verified.
///
/// Regenerate with:  python ml/reference/validate_dsp.py
void main() {
  late Map<String, dynamic> vectors;
  late double fs;

  setUpAll(() {
    final file = File('test/fixtures/golden_vectors.json');
    if (!file.existsSync()) {
      fail('Golden vectors missing. Run: python ml/reference/validate_dsp.py');
    }
    vectors = json.decode(file.readAsStringSync()) as Map<String, dynamic>;
    fs = (vectors['fs'] as num).toDouble();
  });

  test('fixture sample rate matches the BLE contract', () {
    expect(fs, 250.0, reason: 'contracts/ble.md fixes the sample rate at 250 Hz');
  });

  group('golden vectors', () {
    for (final caseName in const [
      'nsr_clean',
      'af_clean',
      'nsr_mains',
      'af_noisy',
    ]) {
      Map<String, dynamic> caseData() => (vectors['cases'] as List)
          .cast<Map<String, dynamic>>()
          .firstWhere((c) => c['name'] == caseName);

      Float64List samples() => Float64List.fromList(
            (caseData()['samples'] as List).map((v) => (v as num).toDouble()).toList(),
          );

      group(caseName, () {
        test('R-peak detection recovers the ground-truth beats', () {
          final c = caseData();
          final truth = (c['groundTruthPeaks'] as List).cast<int>();
          final detected = PanTompkins(fs).detect(samples()).peaks;

          // Match within 50 ms, then require both sensitivity and positive
          // predictive value at 95%. Counting peaks alone would let a detector
          // that finds the wrong beats in the right number pass.
          final tolerance = (0.050 * fs).round();
          final unmatched = List<int>.from(truth);
          var tp = 0;
          for (final d in detected) {
            var best = -1, bestDist = tolerance + 1;
            for (var i = 0; i < unmatched.length; i++) {
              final dist = (d - unmatched[i]).abs();
              if (dist < bestDist) {
                best = i;
                bestDist = dist;
              }
            }
            if (best >= 0) {
              unmatched.removeAt(best);
              tp++;
            }
          }
          final sensitivity = tp / truth.length;
          final ppv = detected.isEmpty ? 0.0 : tp / detected.length;

          expect(sensitivity, greaterThanOrEqualTo(0.95),
              reason: 'missed beats fabricate long RR intervals');
          expect(ppv, greaterThanOrEqualTo(0.95),
              reason: 'spurious beats fabricate short RR intervals, which mimic AF');
        });

        test('RR features match the reference implementation', () {
          final c = caseData();
          final expected = c['expectedRrFeatures'] as Map<String, dynamic>;
          final peaks = PanTompkins(fs).detect(samples());
          final actual = const RrAnalyser().analyse(peaks.rrIntervalsMs(fs));

          expect(actual.count, expected['count'],
              reason: 'interval count must match exactly');
          for (final field in const [
            'meanRrMs',
            'meanHr',
            'rmssdMs',
            'normalisedRmssd',
            'pnn50',
            'normalisedShannonEntropy',
            'irregularityScore',
          ]) {
            expect(actual.toJson()[field] as double,
                closeTo((expected[field] as num).toDouble(), 1e-6),
                reason: '$field diverged from the Python reference');
          }
        });

        test('SQI matches the reference implementation', () {
          final c = caseData();
          final expected = c['expectedSqi'] as Map<String, dynamic>;
          final actual = SqiAnalyser(fs).analyse(samples());

          for (final field in const [
            'score',
            'saturationFraction',
            'flatlineFraction',
            'powerlineRatio',
            'baselineWanderRatio',
          ]) {
            expect(actual.toJson()[field] as double,
                closeTo((expected[field] as num).toDouble(), 1e-6),
                reason: '$field diverged from the Python reference');
          }
        });

        test('irregularity score lands on the correct side of the gate', () {
          final c = caseData();
          final isAf = c['expectedRhythm'] == 'af';
          final peaks = PanTompkins(fs).detect(samples());
          final f = const RrAnalyser().analyse(peaks.rrIntervalsMs(fs));

          if (isAf) {
            expect(f.irregularityScore, greaterThanOrEqualTo(0.5),
                reason: 'simulated AF must clear the irregularity gate');
          } else {
            expect(f.irregularityScore, lessThan(0.5),
                reason: 'simulated sinus rhythm must not clear the gate');
          }
        });
      });
    }
  });
}
