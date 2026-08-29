import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

import 'package:arogyax/core/policy.dart';
import 'package:arogyax/data/record.dart';
import 'package:arogyax/signal/analysis.dart';
import 'package:arogyax/signal/fusion.dart';
import 'package:arogyax/signal/rr_features.dart';
import 'package:arogyax/signal/sqi.dart';

/// Checks ScreeningRecord against contracts/record.schema.json v2.
///
/// The Python mirror (ml/reference/validate_record.py) validates against the
/// schema document itself with a real JSON Schema library. This side checks the
/// things only Dart can see: that the merge with ScreeningAnalysis keeps the
/// field ownership split, and that nothing patient-identifying reaches the
/// upload payload.

ScreeningAnalysis analysis({
  Tier tier = Tier.green,
  DecidedBy decidedBy = DecidedBy.rules,
  PpgCorroboration ppg = PpgCorroboration.none,
  double sqi = 0.82,
  int rrCount = 37,
  double hr = 74,
  double irregularity = 0.11,
  double? cnn,
  bool leadOff = false,
  bool motion = false,
}) {
  final gated = tier == Tier.retake;
  return ScreeningAnalysis(
    decision: TierDecision(tier: tier, decidedBy: decidedBy, ppg: ppg),
    sqi: SqiResult(
      score: sqi,
      saturationFraction: 0,
      flatlineFraction: gated ? 0.9 : 0,
      powerlineRatio: 0.02,
      baselineWanderRatio: 0.05,
      failureReason: gated ? 'flat trace' : null,
    ),
    rr: gated
        ? RrFeatures.empty
        : RrFeatures(
            count: rrCount,
            meanRrMs: 60000 / hr,
            meanHr: hr,
            rmssdMs: 40,
            normalisedRmssd: 0.05,
            pnn50: 0.3,
            normalisedShannonEntropy: 0.6,
            irregularityScore: irregularity,
            rejectedIntervals: 0,
          ),
    rPeaks: const [],
    conditioned: Float64List(0),
    durationSec: 30,
    motionRejected: motion,
    leadOffDetected: leadOff,
    dataGapDetected: false,
    cnnScore: cnn,
  );
}

ScreeningRecord build({
  ScreeningAnalysis? a,
  FusionFeatures? fusion,
  double? lat,
  double? lon,
}) =>
    ScreeningRecord.fromAnalysis(
      a ?? analysis(),
      patientPseudoId: 'a1b2c3d4e5f60718',
      whvId: 'whv-021',
      phcId: 'phc-042',
      fusion: fusion,
      lat: lat,
      lon: lon,
    );

void main() {
  /// The schema is the contract; reading it here means a field added there and
  /// forgotten here fails a test rather than a server request.
  final schema = jsonDecode(
    File('../contracts/record.schema.json').readAsStringSync(),
  ) as Map<String, dynamic>;
  final schemaProps = (schema['properties'] as Map<String, dynamic>).keys.toSet();

  group('schema alignment', () {
    test('the record serialises exactly the schema version it claims', () {
      expect(ScreeningRecord.kSchemaVersion,
          schema['properties']['schemaVersion']['const']);
      expect(build().toJson()['schemaVersion'], 2);
    });

    test('every serialised key exists in the schema', () {
      // additionalProperties is false, so an extra key is a rejected upload.
      expect(build().toJson().keys.toSet().difference(schemaProps), isEmpty);
    });

    test('every required schema field is present and non-null', () {
      final json = build().toJson();
      for (final field in (schema['required'] as List).cast<String>()) {
        expect(json.containsKey(field), isTrue, reason: 'missing $field');
        expect(json[field], isNotNull, reason: '$field is null');
      }
    });

    test('the ppgCorroboration values match the Dart enum', () {
      final fromSchema = (schema['properties']['ppgCorroboration']['enum'] as List)
          .whereType<String>()
          .toSet();
      expect(PpgCorroboration.values.map((e) => e.name).toSet(), fromSchema);
    });
  });

  group('recordId', () {
    test('is a v4 UUID matching the schema pattern', () {
      final pattern = RegExp(schema['properties']['recordId']['pattern'] as String);
      for (var i = 0; i < 200; i++) {
        expect(pattern.hasMatch(ScreeningRecord.newRecordId()), isTrue);
      }
    });

    test('does not repeat', () {
      // Upload idempotency is keyed on this across every phone in a district;
      // a collision merges two patients' referrals into one.
      final ids = {for (var i = 0; i < 5000; i++) ScreeningRecord.newRecordId()};
      expect(ids.length, 5000);
    });
  });

  group('capturedAt', () {
    test('carries a timezone offset, never a bare local time', () {
      // A naive local timestamp read as UTC shifts every Tamil Nadu screening
      // 5h30m earlier, which reads as a worker screening before dawn.
      final iso = build().toJson()['capturedAt'] as String;
      expect(
        RegExp(r'([+-]\d{2}:\d{2}|Z)$').hasMatch(iso),
        isTrue,
        reason: 'no offset in "$iso"',
      );
      expect(DateTime.parse(iso), isNotNull);
    });
  });

  group('merge with ScreeningAnalysis', () {
    test('carries the signal layer values through unchanged', () {
      final a = analysis(hr: 91, irregularity: 0.44, cnn: 0.71, sqi: 0.66);
      final r = build(a: a);

      expect(r.meanHr, 91);
      expect(r.rrIrregularityScore, 0.44);
      expect(r.cnnScore, 0.71);
      expect(r.sqiScore, 0.66);
      expect(r.ecgDurationSec, 30);
      expect(r.decidedBy, 'rules');
    });

    test('a RETAKE carries no interpretation', () {
      // Mirrors the short-circuit in EcgAnalyser.analyse. If these disagree, a
      // rejected window reaches the PHC with a heart rate attached.
      final r = build(
        a: analysis(tier: Tier.retake, decidedBy: DecidedBy.gate, leadOff: true),
      );

      expect(r.tier, 'RETAKE');
      expect(r.decidedBy, 'gate');
      expect(r.meanHr, isNull);
      expect(r.rrIrregularityScore, isNull);
      expect(r.validate(), isEmpty);
    });

    test('records why an escalation happened', () {
      final r = build(
        a: analysis(
          tier: Tier.red,
          decidedBy: DecidedBy.rulesAndCnn,
          ppg: PpgCorroboration.pulseDeficit,
          cnn: 0.88,
        ),
        fusion: const FusionFeatures(
          pulseDeficitBpm: 11.2,
          perfusedBeatFraction: 0.72,
          nonPerfusingBeats: 9,
          medianPttMs: 268,
          valid: true,
        ),
      );

      expect(r.tier, 'RED');
      expect(r.ppgCorroboration, 'pulseDeficit');
      expect(r.pulseDeficitBpm, 11.2);
      expect(r.fusionValid, isTrue);
    });

    test('an invalid fusion result stores nulls, not zeros', () {
      // FusionFeatures.none carries 0.0 in every numeric field. Persisting
      // that would be indistinguishable from a measured zero pulse deficit —
      // a clinical finding invented by a default value.
      final r = build(fusion: FusionFeatures.none);

      expect(r.fusionValid, isFalse);
      expect(r.pulseDeficitBpm, isNull);
      expect(r.perfusedBeatFraction, isNull);
      expect(r.nonPerfusingBeats, isNull);
      expect(r.medianPttMs, isNull);
    });

    test('a record with no GPS fix is still valid', () {
      final r = build();
      expect(r.lat, isNull);
      expect(r.validate(), isEmpty);
    });

    test('starts pending, unacknowledged', () {
      final r = build();
      expect(r.syncState, SyncState.pending);
      expect(r.syncedAt, isNull);
      expect(r.referralState, isNull);
    });
  });

  group('the upload payload', () {
    test('omits every server-owned field', () {
      final acked = build().copyWith(
        syncState: SyncState.synced,
        referralState: ReferralState.seenAtPhc,
        referralUpdatedBy: 'phc-042',
      );

      // A phone offline for six hours holds a stale copy. Sending it back
      // would revert an acknowledgement made an hour ago.
      final payload = acked.toSyncJson();
      expect(payload.containsKey('referralState'), isFalse);
      expect(payload.containsKey('referralUpdatedAt'), isFalse);
      expect(payload.containsKey('referralUpdatedBy'), isFalse);
    });

    test('carries no key outside the schema', () {
      expect(build().toSyncJson().keys.toSet().difference(schemaProps), isEmpty);
    });

    test('carries no patient identifier beyond the pseudo-ID', () {
      // CLAUDE.md non-negotiable 5, enforced mechanically rather than by
      // reading the class and hoping.
      final blob = jsonEncode(build().toSyncJson()).toLowerCase();
      for (final forbidden in const [
        'name', 'phone', 'aadhaar', 'address', 'dob', 'mobile',
      ]) {
        expect(blob.contains(forbidden), isFalse,
            reason: 'payload mentions "$forbidden"');
      }
    });

    test('names no condition', () {
      // Non-negotiable 1. A record reaches the dashboard, so this is not an
      // app-only constraint.
      final blob = jsonEncode(build(a: analysis(tier: Tier.red)).toSyncJson())
          .toLowerCase();
      for (final word in const ['fibrillation', 'arrhythmia', 'diagnos']) {
        expect(blob.contains(word), isFalse, reason: 'payload contains "$word"');
      }
    });
  });

  group('round trip', () {
    test('survives toJson -> fromJson intact', () {
      final original = build(
        a: analysis(tier: Tier.red, decidedBy: DecidedBy.cnn, cnn: 0.9),
        fusion: const FusionFeatures(
          pulseDeficitBpm: 8.4,
          perfusedBeatFraction: 0.81,
          nonPerfusingBeats: 5,
          medianPttMs: 240,
          valid: true,
        ),
        lat: 11.0168,
        lon: 76.9558,
      ).copyWith(
        syncState: SyncState.synced,
        referralState: ReferralState.visitScheduled,
      );

      final back = ScreeningRecord.fromJson(original.toJson());

      expect(back.toJson(), original.toJson());
      expect(back.referralState, ReferralState.visitScheduled);
      expect(back.syncState, SyncState.synced);
      expect(back.pulseDeficitBpm, 8.4);
    });

    test('every ReferralState survives its wire encoding', () {
      for (final s in ReferralState.values) {
        expect(ReferralState.fromWire(s.wire), s);
      }
    });

    test('an unknown referral state decodes to null, not a wrong one', () {
      // Forward compatibility: a server that learns a new state must not cause
      // the phone to display the wrong one.
      expect(ReferralState.fromWire('teleported_to_phc'), isNull);
    });
  });

  group('validate', () {
    test('accepts a well-formed record', () {
      expect(build().validate(), isEmpty);
    });

    test('rejects a malformed recordId', () {
      final r = ScreeningRecord(
        recordId: 'not-a-uuid',
        patientPseudoId: 'a1b2c3d4e5f60718',
        whvId: 'whv-021',
        capturedAt: DateTime.now(),
        ecgDurationSec: 30,
        sqiScore: 0.8,
        motionRejected: false,
        leadOffDetected: false,
        decidedBy: 'rules',
        tier: 'GREEN',
        modelVersion: 'rules-1.0',
      );
      expect(r.validate(), isNotEmpty);
    });

    test('rejects a RETAKE that carries scores', () {
      final r = ScreeningRecord(
        recordId: ScreeningRecord.newRecordId(),
        patientPseudoId: 'a1b2c3d4e5f60718',
        whvId: 'whv-021',
        capturedAt: DateTime.now(),
        ecgDurationSec: 30,
        sqiScore: 0.2,
        motionRejected: false,
        leadOffDetected: true,
        meanHr: 74, // must not survive a gate
        decidedBy: 'gate',
        tier: 'RETAKE',
        modelVersion: 'rules-1.0',
      );
      expect(r.validate(), contains(contains('RETAKE')));
    });
  });
}
