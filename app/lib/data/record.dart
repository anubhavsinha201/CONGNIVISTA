import 'dart:math';

import '../core/policy.dart';
import '../signal/analysis.dart';
import '../signal/fusion.dart';
import '../signal/ppg.dart';

/// Device-side upload state. See contracts/sync.md section 5.
enum SyncState { pending, synced, failed }

/// What the PHC has done about a referral. Server-owned; the device only ever
/// mirrors it back from `GET /v1/acks`.
///
/// A closed enum rather than a free-text note, on purpose. Worker-facing text
/// comes from the static string table (CLAUDE.md non-negotiable 6), and an
/// unreviewed free-text channel from outside is exactly how the words this
/// product must never display would reach a worker's screen (non-negotiable 1).
enum ReferralState {
  none,
  acknowledged,
  patientContacted,
  visitScheduled,
  seenAtPhc,
  closed;

  String get wire => switch (this) {
        ReferralState.none => 'none',
        ReferralState.acknowledged => 'acknowledged',
        ReferralState.patientContacted => 'patient_contacted',
        ReferralState.visitScheduled => 'visit_scheduled',
        ReferralState.seenAtPhc => 'seen_at_phc',
        ReferralState.closed => 'closed',
      };

  static ReferralState? fromWire(String? s) => switch (s) {
        'none' => ReferralState.none,
        'acknowledged' => ReferralState.acknowledged,
        'patient_contacted' => ReferralState.patientContacted,
        'visit_scheduled' => ReferralState.visitScheduled,
        'seen_at_phc' => ReferralState.seenAtPhc,
        'closed' => ReferralState.closed,
        _ => null,
      };
}

/// What the PHC clinician found after seeing a referred patient.
///
/// Server-owned, like [ReferralState], and arriving by the same acks channel.
/// The two are deliberately separate: [ReferralState] records *process* — was
/// the patient contacted, did they attend — while this records the *finding*.
/// Only a finding can be used as a training label, which is why a feedback loop
/// built on referral states alone cannot improve the model.
///
/// A closed enum, never free text and never a condition name. This is the only
/// path by which a clinical judgement reaches a worker's phone, and an
/// unreviewed text field here is exactly how the words this product must never
/// display would land on her screen (CLAUDE.md non-negotiables 1 and 7).
enum ClinicianOutcome {
  /// The clinician confirmed the rhythm finding the referral was raised for.
  confirmed,

  /// The clinician found no such finding. A true negative for the screen.
  notConfirmed,

  /// Seen, but the question was not settled — a poor clinic trace, or the
  /// patient was in sinus rhythm at the time. NOT a negative. Excluding these
  /// from retraining is correct; counting them as negatives would teach the
  /// model that intermittent AF is absence of AF.
  inconclusive;

  String get wire => switch (this) {
        ClinicianOutcome.confirmed => 'confirmed',
        ClinicianOutcome.notConfirmed => 'not_confirmed',
        ClinicianOutcome.inconclusive => 'inconclusive',
      };

  static ClinicianOutcome? fromWire(String? s) => switch (s) {
        'confirmed' => ClinicianOutcome.confirmed,
        'not_confirmed' => ClinicianOutcome.notConfirmed,
        'inconclusive' => ClinicianOutcome.inconclusive,
        _ => null,
      };

  /// Whether this record may be used as a supervised training label.
  /// Only a settled finding qualifies.
  bool get isTrainingLabel =>
      this == ClinicianOutcome.confirmed || this == ClinicianOutcome.notConfirmed;
}

/// One doorstep screening, as stored locally and as uploaded.
///
/// Mirrors contracts/record.schema.json **v4**, field for field. When the schema
/// changes, this class and `ml/reference/validate_record.py` change with it, and
/// the migration in [LocalStore] gets a new step.
///
/// Immutable. A screening is a historical fact; the only field that legitimately
/// changes after capture is the server-owned referral block, and that arrives by
/// [copyWith] rather than mutation.
class ScreeningRecord {
  static const int kSchemaVersion = 4;

  // ---- Identity -----------------------------------------------------------
  final String recordId;
  final String patientPseudoId;
  final String whvId;
  final String? phcId;

  // ---- When and where -----------------------------------------------------
  final DateTime capturedAt;
  final double? lat;
  final double? lon;
  final double? locationAccuracyM;

  // ---- Demographics (schema v4) -------------------------------------------
  // ageBand and villageCode are REQUIRED: a band and a code, not an exact age
  // or a place name, because clinical fields are stored unencrypted by design
  // and an exact age + villageCode + sex together would often identify a
  // specific person in a small village. See contracts/record.schema.json.
  final String ageBand;
  final String villageCode;
  final String? sex;
  final double? systolicBp;
  final double? diastolicBp;
  final double? glucose;

  // ---- Camera / contact PPG ----------------------------------------------
  final String? ppgResult;
  final double? ppgMeanHr;
  final double? ppgIrregularityScore;
  final double? ppgPerfusionIndex;

  // ---- ECG capture and quality -------------------------------------------
  final double ecgDurationSec;
  final double sqiScore;
  final bool motionRejected;
  final bool leadOffDetected;

  // ---- Detector outputs ---------------------------------------------------
  final double? meanHr;
  final int? rrIntervalCount;
  final double? rrIrregularityScore;
  final double? cnnScore;
  final String decidedBy;

  // ---- Fusion evidence ----------------------------------------------------
  final double? pulseDeficitBpm;
  final double? perfusedBeatFraction;
  final int? nonPerfusingBeats;
  final double? medianPttMs;
  final bool fusionValid;
  final bool fusionImplausible;

  /// Why a YELLOW or ORANGE became a RED, when it did. Without this the PHC
  /// sees a referral it cannot account for.
  final String? ppgCorroboration;

  // ---- The answer ---------------------------------------------------------
  final String tier;
  final String modelVersion;
  final String? ecgWaveformRef;

  // ---- Device bookkeeping -------------------------------------------------
  final SyncState syncState;
  final DateTime? syncedAt;

  // ---- Server-owned -------------------------------------------------------
  final ReferralState? referralState;
  final DateTime? referralUpdatedAt;
  final String? referralUpdatedBy;

  /// Server-owned clinical finding. Null until the PHC records one.
  final ClinicianOutcome? clinicianOutcome;
  final DateTime? clinicianOutcomeAt;

  const ScreeningRecord({
    required this.recordId,
    required this.patientPseudoId,
    required this.whvId,
    this.phcId,
    required this.capturedAt,
    this.lat,
    this.lon,
    this.locationAccuracyM,
    required this.ageBand,
    required this.villageCode,
    this.sex,
    this.systolicBp,
    this.diastolicBp,
    this.glucose,
    this.ppgResult,
    this.ppgMeanHr,
    this.ppgIrregularityScore,
    this.ppgPerfusionIndex,
    required this.ecgDurationSec,
    required this.sqiScore,
    required this.motionRejected,
    required this.leadOffDetected,
    this.meanHr,
    this.rrIntervalCount,
    this.rrIrregularityScore,
    this.cnnScore,
    required this.decidedBy,
    this.pulseDeficitBpm,
    this.perfusedBeatFraction,
    this.nonPerfusingBeats,
    this.medianPttMs,
    this.fusionValid = false,
    this.fusionImplausible = false,
    this.ppgCorroboration,
    required this.tier,
    required this.modelVersion,
    this.ecgWaveformRef,
    this.syncState = SyncState.pending,
    this.syncedAt,
    this.referralState,
    this.referralUpdatedAt,
    this.referralUpdatedBy,
    this.clinicianOutcome,
    this.clinicianOutcomeAt,
  });

  /// The one construction path from a completed capture.
  ///
  /// [analysis] supplies every signal-owned field through
  /// [ScreeningAnalysis.toRecordFields]; this merges in identity, location and
  /// sync state. The split is the one already documented at that method.
  ///
  /// [now] and [newId] are injectable so the tests are deterministic. Nothing
  /// else in the app passes them.
  factory ScreeningRecord.fromAnalysis(
    ScreeningAnalysis analysis, {
    required String patientPseudoId,
    required String whvId,
    String? phcId,
    PpgResult? ppg,
    FusionFeatures? fusion,
    double? lat,
    double? lon,
    double? locationAccuracyM,
    required String ageBand,
    required String villageCode,
    String? sex,
    double? systolicBp,
    double? diastolicBp,
    double? glucose,
    String? ecgWaveformRef,
    DateTime? now,
    String? newId,
  }) {
    final f = analysis.toRecordFields();
    final fusionOk = fusion != null && fusion.valid;

    return ScreeningRecord(
      recordId: newId ?? newRecordId(),
      patientPseudoId: patientPseudoId,
      whvId: whvId,
      phcId: phcId,

      // Local time WITH offset. A record timestamped in UTC and rendered
      // naively shows a 05:30 shift on the PHC dashboard, which reads as a
      // worker screening patients before dawn.
      capturedAt: now ?? DateTime.now(),

      lat: lat,
      lon: lon,
      locationAccuracyM: locationAccuracyM,

      ageBand: ageBand,
      villageCode: villageCode,
      sex: sex,
      systolicBp: systolicBp,
      diastolicBp: diastolicBp,
      glucose: glucose,

      ppgResult: ppg?.prescreenOutcome,
      ppgMeanHr: ppg == null || !ppg.usable ? null : ppg.meanPulseRate,
      ppgIrregularityScore:
          ppg == null || !ppg.usable ? null : ppg.irregularityScore,
      ppgPerfusionIndex: ppg?.perfusionIndex,

      ecgDurationSec: f['ecgDurationSec'] as double,
      sqiScore: f['sqiScore'] as double,
      motionRejected: f['motionRejected'] as bool,
      leadOffDetected: f['leadOffDetected'] as bool,
      meanHr: f['meanHr'] as double?,
      rrIntervalCount: f['rrIntervalCount'] as int?,
      rrIrregularityScore: f['rrIrregularityScore'] as double?,
      cnnScore: f['cnnScore'] as double?,
      decidedBy: f['decidedBy'] as String,

      // Guarded on `valid`, not merely on non-null. An invalid FusionFeatures
      // still carries zeros in every numeric field, and a stored 0.0 pulse
      // deficit is indistinguishable from a measured one that happened to be
      // zero. Null is the only honest value for "not measured".
      pulseDeficitBpm: fusionOk ? fusion.pulseDeficitBpm : null,
      perfusedBeatFraction: fusionOk ? fusion.perfusedBeatFraction : null,
      nonPerfusingBeats: fusionOk ? fusion.nonPerfusingBeats : null,
      medianPttMs: fusionOk ? fusion.medianPttMs : null,
      fusionValid: fusionOk,
      fusionImplausible: fusion?.implausible ?? false,
      ppgCorroboration: analysis.decision.ppg.name,

      tier: f['tier'] as String,
      modelVersion: f['modelVersion'] as String,
      ecgWaveformRef: ecgWaveformRef,

      syncState: SyncState.pending,
    );
  }

  ScreeningRecord copyWith({
    SyncState? syncState,
    DateTime? syncedAt,
    ReferralState? referralState,
    DateTime? referralUpdatedAt,
    String? referralUpdatedBy,
    ClinicianOutcome? clinicianOutcome,
    DateTime? clinicianOutcomeAt,
  }) =>
      ScreeningRecord(
        recordId: recordId,
        patientPseudoId: patientPseudoId,
        whvId: whvId,
        phcId: phcId,
        capturedAt: capturedAt,
        lat: lat,
        lon: lon,
        locationAccuracyM: locationAccuracyM,
        ageBand: ageBand,
        villageCode: villageCode,
        sex: sex,
        systolicBp: systolicBp,
        diastolicBp: diastolicBp,
        glucose: glucose,
        ppgResult: ppgResult,
        ppgMeanHr: ppgMeanHr,
        ppgIrregularityScore: ppgIrregularityScore,
        ppgPerfusionIndex: ppgPerfusionIndex,
        ecgDurationSec: ecgDurationSec,
        sqiScore: sqiScore,
        motionRejected: motionRejected,
        leadOffDetected: leadOffDetected,
        meanHr: meanHr,
        rrIntervalCount: rrIntervalCount,
        rrIrregularityScore: rrIrregularityScore,
        cnnScore: cnnScore,
        decidedBy: decidedBy,
        pulseDeficitBpm: pulseDeficitBpm,
        perfusedBeatFraction: perfusedBeatFraction,
        nonPerfusingBeats: nonPerfusingBeats,
        medianPttMs: medianPttMs,
        fusionValid: fusionValid,
        fusionImplausible: fusionImplausible,
        ppgCorroboration: ppgCorroboration,
        tier: tier,
        modelVersion: modelVersion,
        ecgWaveformRef: ecgWaveformRef,
        syncState: syncState ?? this.syncState,
        syncedAt: syncedAt ?? this.syncedAt,
        referralState: referralState ?? this.referralState,
        referralUpdatedAt: referralUpdatedAt ?? this.referralUpdatedAt,
        referralUpdatedBy: referralUpdatedBy ?? this.referralUpdatedBy,
        clinicianOutcome: clinicianOutcome ?? this.clinicianOutcome,
        clinicianOutcomeAt: clinicianOutcomeAt ?? this.clinicianOutcomeAt,
      );

  /// Full v2 record, including the server-owned referral block. Used for local
  /// storage and for tests. **Not** what goes on the wire — see [toSyncJson].
  Map<String, dynamic> toJson() => {
        'recordId': recordId,
        'schemaVersion': kSchemaVersion,
        'patientPseudoId': patientPseudoId,
        'whvId': whvId,
        if (phcId != null) 'phcId': phcId,
        'capturedAt': _iso(capturedAt),
        'lat': lat,
        'lon': lon,
        'locationAccuracyM': locationAccuracyM,
        'ageBand': ageBand,
        'villageCode': villageCode,
        'sex': sex,
        'systolicBp': systolicBp,
        'diastolicBp': diastolicBp,
        'glucose': glucose,
        'ppgResult': ppgResult,
        'ppgMeanHr': ppgMeanHr,
        'ppgIrregularityScore': ppgIrregularityScore,
        'ppgPerfusionIndex': ppgPerfusionIndex,
        'ecgDurationSec': ecgDurationSec,
        'sqiScore': sqiScore,
        'motionRejected': motionRejected,
        'leadOffDetected': leadOffDetected,
        'meanHr': meanHr,
        'rrIntervalCount': rrIntervalCount,
        'rrIrregularityScore': rrIrregularityScore,
        'cnnScore': cnnScore,
        'decidedBy': decidedBy,
        'pulseDeficitBpm': pulseDeficitBpm,
        'perfusedBeatFraction': perfusedBeatFraction,
        'nonPerfusingBeats': nonPerfusingBeats,
        'medianPttMs': medianPttMs,
        'fusionValid': fusionValid,
        'fusionImplausible': fusionImplausible,
        'ppgCorroboration': ppgCorroboration,
        'tier': tier,
        'modelVersion': modelVersion,
        'ecgWaveformRef': ecgWaveformRef,
        'syncState': syncState.name,
        'syncedAt': syncedAt == null ? null : _iso(syncedAt!),
        'referralState': referralState?.wire,
        'referralUpdatedAt':
            referralUpdatedAt == null ? null : _iso(referralUpdatedAt!),
        'referralUpdatedBy': referralUpdatedBy,
        'clinicianOutcome': clinicianOutcome?.wire,
        'clinicianOutcomeAt': _iso(clinicianOutcomeAt),
      };

  /// The upload payload: the full record minus the server-owned referral block.
  ///
  /// A phone that has been out of coverage for six hours holds a stale copy of
  /// `referralState`. Sending it would let a retry silently revert an
  /// acknowledgement a PHC nurse made an hour ago. The server strips these
  /// fields on ingest too — this is the belt, that is the braces.
  Map<String, dynamic> toSyncJson() {
    final j = toJson();
    j.remove('referralState');
    j.remove('referralUpdatedAt');
    j.remove('referralUpdatedBy');
    j.remove('clinicianOutcome');
    j.remove('clinicianOutcomeAt');
    return j;
  }

  factory ScreeningRecord.fromJson(Map<String, dynamic> j) => ScreeningRecord(
        recordId: j['recordId'] as String,
        patientPseudoId: j['patientPseudoId'] as String,
        whvId: j['whvId'] as String,
        phcId: j['phcId'] as String?,
        capturedAt: DateTime.parse(j['capturedAt'] as String),
        lat: _d(j['lat']),
        lon: _d(j['lon']),
        locationAccuracyM: _d(j['locationAccuracyM']),
        ageBand: j['ageBand'] as String,
        villageCode: j['villageCode'] as String,
        sex: j['sex'] as String?,
        systolicBp: _d(j['systolicBp']),
        diastolicBp: _d(j['diastolicBp']),
        glucose: _d(j['glucose']),
        ppgResult: j['ppgResult'] as String?,
        ppgMeanHr: _d(j['ppgMeanHr']),
        ppgIrregularityScore: _d(j['ppgIrregularityScore']),
        ppgPerfusionIndex: _d(j['ppgPerfusionIndex']),
        ecgDurationSec: _d(j['ecgDurationSec']) ?? 0,
        sqiScore: _d(j['sqiScore']) ?? 0,
        motionRejected: j['motionRejected'] as bool? ?? false,
        leadOffDetected: j['leadOffDetected'] as bool? ?? false,
        meanHr: _d(j['meanHr']),
        rrIntervalCount: j['rrIntervalCount'] as int?,
        rrIrregularityScore: _d(j['rrIrregularityScore']),
        cnnScore: _d(j['cnnScore']),
        decidedBy: j['decidedBy'] as String? ?? 'gate',
        pulseDeficitBpm: _d(j['pulseDeficitBpm']),
        perfusedBeatFraction: _d(j['perfusedBeatFraction']),
        nonPerfusingBeats: j['nonPerfusingBeats'] as int?,
        medianPttMs: _d(j['medianPttMs']),
        fusionValid: j['fusionValid'] as bool? ?? false,
        fusionImplausible: j['fusionImplausible'] as bool? ?? false,
        ppgCorroboration: j['ppgCorroboration'] as String?,
        tier: j['tier'] as String,
        modelVersion: j['modelVersion'] as String,
        ecgWaveformRef: j['ecgWaveformRef'] as String?,
        syncState: SyncState.values.firstWhere(
          (s) => s.name == j['syncState'],
          orElse: () => SyncState.pending,
        ),
        syncedAt: _dt(j['syncedAt']),
        referralState: ReferralState.fromWire(j['referralState'] as String?),
        referralUpdatedAt: _dt(j['referralUpdatedAt']),
        referralUpdatedBy: j['referralUpdatedBy'] as String?,
        clinicianOutcome:
            ClinicianOutcome.fromWire(j['clinicianOutcome'] as String?),
        clinicianOutcomeAt: _dt(j['clinicianOutcomeAt']),
      );

  /// Cheap structural check, run before a record is queued.
  ///
  /// This is not a substitute for the schema — `ml/reference/validate_record.py`
  /// does that properly. This catches the handful of ways a caller can produce
  /// a record the server will reject, at the point where the caller can still
  /// be blamed for it.
  List<String> validate() {
    final errors = <String>[];
    if (!_uuidV4.hasMatch(recordId)) {
      errors.add('recordId is not a v4 UUID: $recordId');
    }
    if (patientPseudoId.length < 8) {
      errors.add('patientPseudoId shorter than 8 characters');
    }
    if (whvId.isEmpty) errors.add('whvId is empty');
    if (!const {'45-54', '55-64', '65-74', '75+'}.contains(ageBand)) {
      errors.add('ageBand not in the enum: $ageBand');
    }
    if (villageCode.isEmpty) errors.add('villageCode is empty');
    if (!const {'RED', 'ORANGE', 'YELLOW', 'GREEN', 'RETAKE'}.contains(tier)) {
      errors.add('tier not in the enum: $tier');
    }
    if (!const {'rules', 'cnn', 'rules+cnn', 'gate', 'history'}
        .contains(decidedBy)) {
      errors.add('decidedBy not in the enum: $decidedBy');
    }
    if (sqiScore < 0 || sqiScore > 1) errors.add('sqiScore outside 0..1');
    if (modelVersion.isEmpty) errors.add('modelVersion is empty');

    // A gated window must carry no interpretation. This mirrors the
    // short-circuit in EcgAnalyser.analyse; if the two ever disagree, a RETAKE
    // could reach the dashboard with a heart rate attached to it.
    if (tier == 'RETAKE' && (meanHr != null || rrIrregularityScore != null)) {
      errors.add('RETAKE record carries scores');
    }
    return errors;
  }

  /// ISO 8601 **with a timezone offset**, never a bare UTC `Z`.
  ///
  /// `DateTime.toIso8601String()` on a local DateTime emits no offset at all,
  /// which a downstream parser is free to read as UTC — putting every Tamil
  /// Nadu screening 5h30m earlier than it happened.
  static String _iso(DateTime t) {
    if (t.isUtc) return t.toIso8601String();
    final o = t.timeZoneOffset;
    final sign = o.isNegative ? '-' : '+';
    final h = o.inHours.abs().toString().padLeft(2, '0');
    final m = (o.inMinutes.abs() % 60).toString().padLeft(2, '0');
    return '${t.toIso8601String()}$sign$h:$m';
  }

  static double? _d(Object? v) => v == null ? null : (v as num).toDouble();
  static DateTime? _dt(Object? v) =>
      v == null ? null : DateTime.parse(v as String);

  static final RegExp _uuidV4 = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
  );

  static final Random _rng = Random.secure();

  /// RFC 4122 version 4 UUID, matching the pattern in record.schema.json.
  ///
  /// Hand-rolled rather than pulling in a package: it is nine lines, and the
  /// schema pins the exact shape so a mistake fails a test rather than
  /// escaping. `Random.secure` because these are the collision domain for
  /// upload idempotency across every phone in a district.
  static String newRecordId() {
    final b = List<int>.generate(16, (_) => _rng.nextInt(256));
    b[6] = (b[6] & 0x0f) | 0x40; // version 4
    b[8] = (b[8] & 0x3f) | 0x80; // variant 10xx
    final hex = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
        '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
  }
}

/// One referral state change, pulled from `GET /v1/acks`.
class ReferralAck {
  final String recordId;
  final ReferralState referralState;
  final DateTime? referralUpdatedAt;
  final String? referralUpdatedBy;

  /// The clinical finding, when the PHC has recorded one. Arrives on the
  /// same channel as the referral state because it is the same event: a
  /// clinician acting on a referral.
  final ClinicianOutcome? clinicianOutcome;
  final DateTime? clinicianOutcomeAt;

  const ReferralAck({
    required this.recordId,
    required this.referralState,
    this.referralUpdatedAt,
    this.referralUpdatedBy,
    this.clinicianOutcome,
    this.clinicianOutcomeAt,
  });

  factory ReferralAck.fromJson(Map<String, dynamic> j) => ReferralAck(
        recordId: j['recordId'] as String,
        referralState:
            ReferralState.fromWire(j['referralState'] as String?) ??
                ReferralState.none,
        referralUpdatedAt: ScreeningRecord._dt(j['referralUpdatedAt']),
        referralUpdatedBy: j['referralUpdatedBy'] as String?,
      );
}
