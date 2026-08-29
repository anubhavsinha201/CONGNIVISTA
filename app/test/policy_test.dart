import 'package:flutter_test/flutter_test.dart';

import 'package:arogyax/core/policy.dart';

/// Table test over contracts/tiers.md. Every row of that table and every gate
/// has a case here, so the document and the code cannot drift apart silently.

TierInputs inputs({
  double sqi = 0.9,
  bool motion = false,
  bool leadOff = false,
  bool gap = false,
  int rrCount = 40,
  double hr = 72,
  double irregularity = 0.1,
  double? cnn,
  bool historyIntermittent = false,
  bool historyPersistent = false,
}) =>
    TierInputs(
      sqiScore: sqi,
      motionRejected: motion,
      leadOffDetected: leadOff,
      dataGapDetected: gap,
      rrIntervalCount: rrCount,
      meanHr: hr,
      rrIrregularityScore: irregularity,
      cnnScore: cnn,
      historyIntermittent: historyIntermittent,
      historyPersistent: historyPersistent,
    );

void main() {
  group('gates take precedence over any score', () {
    test('detached electrode', () {
      final d = Policy.decide(inputs(leadOff: true, irregularity: 0.99, hr: 150));
      expect(d.tier, Tier.retake);
      expect(d.retakeReason, RetakeReason.electrodeDetached);
    });

    test('dropped BLE data', () {
      // contracts/ble.md section 3: a sequence gap deletes signal and
      // manufactures a short RR interval, which looks exactly like AF.
      final d = Policy.decide(inputs(gap: true, irregularity: 0.99));
      expect(d.tier, Tier.retake);
      expect(d.retakeReason, RetakeReason.droppedData);
    });

    test('patient moved', () {
      final d = Policy.decide(inputs(motion: true, irregularity: 0.99));
      expect(d.tier, Tier.retake);
      expect(d.retakeReason, RetakeReason.patientMoved);
    });

    test('signal quality below the gate', () {
      final d = Policy.decide(inputs(sqi: 0.49, irregularity: 0.99));
      expect(d.tier, Tier.retake);
      expect(d.retakeReason, RetakeReason.poorSignalQuality);
    });

    test('too few beats', () {
      final d = Policy.decide(inputs(rrCount: 29, irregularity: 0.99));
      expect(d.tier, Tier.retake);
      expect(d.retakeReason, RetakeReason.tooFewBeats);
    });

    test('a gated window is never attributed to a detector', () {
      expect(Policy.decide(inputs(motion: true)).decidedBy, DecidedBy.gate);
    });

    test('sqi exactly at the gate passes', () {
      expect(Policy.decide(inputs(sqi: Policy.kSqiGate)).tier, Tier.green);
    });

    test('exactly the minimum beat count passes', () {
      expect(
          Policy.decide(inputs(rrCount: Policy.kMinRrIntervals)).tier, Tier.green);
    });
  });

  group('tier table', () {
    test('regular rhythm, normal rate -> GREEN', () {
      expect(Policy.decide(inputs(irregularity: 0.2, hr: 72)).tier, Tier.green);
    });

    test('irregular, normal rate, first time -> YELLOW', () {
      expect(Policy.decide(inputs(irregularity: 0.8, hr: 72)).tier, Tier.yellow);
    });

    test('irregular, tachycardic -> RED', () {
      expect(Policy.decide(inputs(irregularity: 0.8, hr: 140)).tier, Tier.red);
    });

    test('irregular, bradycardic -> RED', () {
      expect(Policy.decide(inputs(irregularity: 0.8, hr: 42)).tier, Tier.red);
    });

    test('abnormal rate alone does NOT escalate', () {
      // Rate decides how urgently, not whether. A fast but regular rhythm is
      // not what this instrument screens for.
      expect(Policy.decide(inputs(irregularity: 0.2, hr: 150)).tier, Tier.green);
    });

    test('irregularity exactly at the gate escalates', () {
      expect(
          Policy.decide(inputs(irregularity: Policy.kRrIrregularityGate)).tier,
          Tier.yellow);
    });

    test('rate boundaries are inclusive of the normal range', () {
      expect(Policy.decide(inputs(irregularity: 0.8, hr: Policy.kHrLow)).tier,
          Tier.yellow);
      expect(Policy.decide(inputs(irregularity: 0.8, hr: Policy.kHrHigh)).tier,
          Tier.yellow);
    });
  });

  group('five-state triage: history (contracts/tiers.md section 2)', () {
    test('irregular, normal rate, repeated across visits -> ORANGE', () {
      final d = Policy.decide(
          inputs(irregularity: 0.8, hr: 72, historyIntermittent: true));
      expect(d.tier, Tier.orange);
      expect(d.decidedBy, DecidedBy.rules);
    });

    test('irregular, normal rate, persistent history -> ORANGE too', () {
      final d = Policy.decide(
          inputs(irregularity: 0.8, hr: 72, historyPersistent: true));
      expect(d.tier, Tier.orange);
    });

    test('a clean visit stays GREEN with no history', () {
      expect(Policy.decide(inputs(irregularity: 0.1, hr: 72)).tier,
          Tier.green);
    });

    test('a clean visit is escalated to ORANGE by an intermittent history',
        () {
      final d = Policy.decide(
          inputs(irregularity: 0.1, hr: 72, historyIntermittent: true));
      expect(d.tier, Tier.orange);
      expect(d.decidedBy, DecidedBy.history);
    });

    test('a clean visit is NOT escalated by a merely persistent history', () {
      // "Always flagged before, clean today" reads as a real result, not as
      // evidence of a hidden episode - see contracts/tiers.md section 2.
      final d = Policy.decide(
          inputs(irregularity: 0.1, hr: 72, historyPersistent: true));
      expect(d.tier, Tier.green);
    });

    test('an abnormal rate still reaches RED regardless of history', () {
      final d = Policy.decide(inputs(
          irregularity: 0.8, hr: 140, historyIntermittent: true));
      expect(d.tier, Tier.red);
    });
  });

  group('detector combination', () {
    test('rules alone can escalate when the CNN has not shipped', () {
      final d = Policy.decide(inputs(irregularity: 0.8));
      expect(d.tier, Tier.yellow);
      expect(d.decidedBy, DecidedBy.rules);
    });

    test('a CNN score is ignored while the INT8 threshold is uncalibrated', () {
      // Policy.kCnnThresholdInt8 is null until the calibration notebook has
      // been run. We do not fall back to a guessed threshold — an uncalibrated
      // operating point is exactly the failure this product exists to name.
      final d = Policy.decide(inputs(irregularity: 0.1, cnn: 0.99));
      expect(d.tier, Tier.green);
      expect(d.decidedBy, DecidedBy.rules);
    }, skip: Policy.kCnnThresholdInt8 != null
        ? 'INT8 threshold now calibrated; this test covered the pre-calibration state'
        : null);

    // The threshold IS calibrated now (0.1875, refit 2026-08-29 against the
    // current seed-0 model — see the doc comment on kCnnThresholdInt8). These
    // replace the skipped test above with real coverage of the path that
    // actually ships, mirrored in ml/reference/validate_policy.py.
    test('a high CNN score alone escalates past GREEN, attributed to the CNN',
        () {
      final d = Policy.decide(inputs(irregularity: 0.1, cnn: 0.99, hr: 72));
      expect(d.tier, isNot(Tier.green));
      expect(d.decidedBy, DecidedBy.cnn);
    });

    test('a CNN score below threshold does not escalate on its own', () {
      final d = Policy.decide(inputs(irregularity: 0.1, cnn: 0.10, hr: 72));
      expect(d.tier, Tier.green);
    });

    test('rules and CNN agreeing is attributed to both', () {
      final d = Policy.decide(inputs(irregularity: 0.8, cnn: 0.99, hr: 72));
      expect(d.decidedBy, DecidedBy.rulesAndCnn);
    });

    test('a CNN score exactly at the gate escalates', () {
      final d = Policy.decide(
          inputs(irregularity: 0.1, cnn: Policy.kCnnThresholdInt8, hr: 72));
      expect(d.tier, isNot(Tier.green));
    });

    test('version string records which detectors ran', () {
      expect(Policy.versionFor(DecidedBy.rules), Policy.kRulesVersion);
      expect(Policy.versionFor(DecidedBy.rulesAndCnn), contains(Policy.kCnnVersion));
      // A history-driven ORANGE ran no CNN — only this visit's own rules
      // (clean) plus the patient's history.
      expect(Policy.versionFor(DecidedBy.history), Policy.kRulesVersion);
    });
  });

  group('safety invariants', () {
    test('no tier name leaks a diagnosis', () {
      // contracts/tiers.md section 1. The worker-facing vocabulary is urgency,
      // never a condition.
      const forbidden = ['fibrillation', 'af', 'arrhythmia', 'atrial'];
      for (final t in Tier.values) {
        final name = t.name.toLowerCase();
        for (final word in forbidden) {
          expect(name.contains(word), isFalse,
              reason: '"${t.name}" must not contain "$word"');
        }
      }
    });

    test('every retake carries an actionable hint for the worker', () {
      final cases = [
        inputs(leadOff: true),
        inputs(gap: true),
        inputs(motion: true),
        inputs(sqi: 0.1),
        inputs(rrCount: 5),
      ];
      for (final c in cases) {
        final d = Policy.decide(c);
        expect(d.tier, Tier.retake);
        expect(d.retakeHint, isNotNull);
        expect(d.retakeHint, isNotEmpty);
      }
    });
  });
}
