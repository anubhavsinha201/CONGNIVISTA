import 'dart:async';

import 'package:sqlite3/common.dart';

import 'record.dart';

/// The offline record queue.
///
/// This is the piece that makes "offline is the product, not a mode" true. A
/// screening is durable here before the worker sees the tier, and it stays here
/// — through reboots, battery pulls and a whole shift out of coverage — until a
/// server acks it individually.
///
/// ## Why hand-written SQL rather than drift
///
/// drift's table DSL requires a `build_runner` codegen step. The schema here is
/// one table and about a dozen statements, so codegen buys ordering and typing
/// we would otherwise write once, at the cost of a generated file that must be
/// regenerated before anything compiles. The store's public API is what the
/// rest of the app sees, and it is unchanged either way — swapping to drift
/// later is a single-file change with no callers touched.
///
/// Writes are synchronous (`package:sqlite3`) and the async signatures are for
/// the caller's benefit: they let this become drift, or move to an isolate,
/// without a ripple through the app.
class LocalStore {
  /// Bumped with contracts/record.schema.json. See [_migrate].
  static const int kSchemaVersion = 3;

  final CommonDatabase _db;
  final _pending = StreamController<int>.broadcast();

  LocalStore(this._db) {
    _migrate();
  }

  /// Opens the tables and applies migrations.
  ///
  /// `user_version` is sqlite's own integer, so there is no bootstrap table to
  /// create before the migration can decide what to do.
  void _migrate() {
    final from = _db.select('PRAGMA user_version;').first.values.first as int;

    if (from == 0) {
      _db.execute('''
        CREATE TABLE records (
          recordId             TEXT PRIMARY KEY NOT NULL,
          patientPseudoId      TEXT NOT NULL,
          whvId                TEXT NOT NULL,
          phcId                TEXT,
          capturedAt           TEXT NOT NULL,
          lat                  REAL,
          lon                  REAL,
          locationAccuracyM    REAL,
          ppgResult            TEXT,
          ppgMeanHr            REAL,
          ecgDurationSec       REAL NOT NULL,
          sqiScore             REAL NOT NULL,
          motionRejected       INTEGER NOT NULL,
          leadOffDetected      INTEGER NOT NULL,
          meanHr               REAL,
          rrIntervalCount      INTEGER,
          rrIrregularityScore  REAL,
          cnnScore             REAL,
          decidedBy            TEXT NOT NULL,
          tier                 TEXT NOT NULL,
          modelVersion         TEXT NOT NULL,
          ecgWaveformRef       TEXT,
          syncState            TEXT NOT NULL,
          syncedAt             TEXT,
          -- local-only bookkeeping, never uploaded (contracts/sync.md section 7)
          attemptCount         INTEGER NOT NULL DEFAULT 0,
          nextRetryAt          TEXT,
          lastErrorCode        TEXT
        );
      ''');
      _db.execute('CREATE INDEX idx_records_sync ON records(syncState);');
      _db.execute('CREATE INDEX idx_records_captured ON records(capturedAt);');
      _db.execute('PRAGMA user_version = 1;');
    }

    if (_currentVersion < 2) {
      // v1 -> v2. Every column is nullable or defaulted, so existing rows stay
      // valid and keep their referrals; nothing is rewritten.
      for (final col in const [
        'ppgIrregularityScore REAL',
        'ppgPerfusionIndex REAL',
        'pulseDeficitBpm REAL',
        'perfusedBeatFraction REAL',
        'nonPerfusingBeats INTEGER',
        'medianPttMs REAL',
        'fusionValid INTEGER NOT NULL DEFAULT 0',
        'fusionImplausible INTEGER NOT NULL DEFAULT 0',
        'ppgCorroboration TEXT',
        'referralState TEXT',
        'referralUpdatedAt TEXT',
        'referralUpdatedBy TEXT',
      ]) {
        _db.execute('ALTER TABLE records ADD COLUMN $col;');
      }
      _db.execute('PRAGMA user_version = 2;');
    }

    if (_currentVersion < 3) {
      // v2 -> v3. The clinical finding, distinct from the referral process.
      // Nullable, so existing rows stay valid and keep everything they had.
      for (final col in const [
        'clinicianOutcome TEXT',
        'clinicianOutcomeAt TEXT',
      ]) {
        _db.execute('ALTER TABLE records ADD COLUMN $col;');
      }
      // The WHV's round is "who is due", which is a per-patient question over
      // history. Without this index that scan is a full table read on every
      // doorstep, on a budget phone.
      _db.execute(
          'CREATE INDEX IF NOT EXISTS idx_patient_time '
          'ON records (patientPseudoId, capturedAt DESC);');
      _db.execute('PRAGMA user_version = 3;');
    }
  }

  int get _currentVersion =>
      _db.select('PRAGMA user_version;').first.values.first as int;

  // ---- Writes -------------------------------------------------------------

  /// The only write path from a capture. Never awaited by the capture screen
  /// beyond its own completion — and never gated on connectivity.
  Future<void> insert(ScreeningRecord r) async {
    final errors = r.validate();
    if (errors.isNotEmpty) {
      throw ArgumentError('refusing to queue an invalid record: '
          '${errors.join('; ')}');
    }

    final j = r.toJson();
    _db.execute(
      '''
      INSERT OR REPLACE INTO records (
        recordId, patientPseudoId, whvId, phcId, capturedAt,
        lat, lon, locationAccuracyM,
        ppgResult, ppgMeanHr, ppgIrregularityScore, ppgPerfusionIndex,
        ecgDurationSec, sqiScore, motionRejected, leadOffDetected,
        meanHr, rrIntervalCount, rrIrregularityScore, cnnScore, decidedBy,
        pulseDeficitBpm, perfusedBeatFraction, nonPerfusingBeats, medianPttMs,
        fusionValid, fusionImplausible, ppgCorroboration,
        tier, modelVersion, ecgWaveformRef,
        syncState, syncedAt, attemptCount, nextRetryAt, lastErrorCode,
        referralState, referralUpdatedAt, referralUpdatedBy,
        clinicianOutcome, clinicianOutcomeAt
      ) VALUES (${List.filled(41, '?').join(',')});
      ''',
      [
        j['recordId'], j['patientPseudoId'], j['whvId'], j['phcId'],
        j['capturedAt'],
        j['lat'], j['lon'], j['locationAccuracyM'],
        j['ppgResult'], j['ppgMeanHr'], j['ppgIrregularityScore'],
        j['ppgPerfusionIndex'],
        j['ecgDurationSec'], j['sqiScore'],
        _b(j['motionRejected']), _b(j['leadOffDetected']),
        j['meanHr'], j['rrIntervalCount'], j['rrIrregularityScore'],
        j['cnnScore'], j['decidedBy'],
        j['pulseDeficitBpm'], j['perfusedBeatFraction'],
        j['nonPerfusingBeats'], j['medianPttMs'],
        _b(j['fusionValid']), _b(j['fusionImplausible']), j['ppgCorroboration'],
        j['tier'], j['modelVersion'], j['ecgWaveformRef'],
        j['syncState'], j['syncedAt'], 0, null, null,
        j['referralState'], j['referralUpdatedAt'], j['referralUpdatedBy'],
        j['clinicianOutcome'], j['clinicianOutcomeAt'],
      ],
    );
    _emitPending();
  }

  Future<void> markSynced(Iterable<String> recordIds, DateTime at) async {
    if (recordIds.isEmpty) return;
    final stmt = _db.prepare('''
      UPDATE records
         SET syncState = 'synced', syncedAt = ?, nextRetryAt = NULL,
             lastErrorCode = NULL
       WHERE recordId = ?;
    ''');
    try {
      final iso = at.toIso8601String();
      for (final id in recordIds) {
        stmt.execute([iso, id]);
      }
    } finally {
      stmt.dispose();
    }
    _emitPending();
  }

  /// A transport failure or a `retryable` result: stays [SyncState.pending] and
  /// comes back after the backoff. Only the attempt counter moves.
  Future<void> markRetryable(String recordId, DateTime nextRetryAt,
      {String? errorCode}) async {
    _db.execute(
      '''
      UPDATE records
         SET attemptCount = attemptCount + 1, nextRetryAt = ?,
             lastErrorCode = ?, syncState = 'pending'
       WHERE recordId = ?;
      ''',
      [nextRetryAt.toIso8601String(), errorCode, recordId],
    );
    _emitPending();
  }

  /// A `rejected` result: the record is malformed and retrying cannot fix it,
  /// so it leaves the queue. It stays on the device for inspection — a record
  /// the server will not take is a bug worth being able to look at, not
  /// something to delete quietly.
  Future<void> markFailed(String recordId, {String? errorCode}) async {
    _db.execute(
      '''
      UPDATE records
         SET syncState = 'failed', attemptCount = attemptCount + 1,
             nextRetryAt = NULL, lastErrorCode = ?
       WHERE recordId = ?;
      ''',
      [errorCode, recordId],
    );
    _emitPending();
  }

  /// Clears every pending backoff deadline, making all pending records due now.
  ///
  /// Does **not** touch `attemptCount`: the ladder position is a property of
  /// how often the server has refused, and a worker walking back into coverage
  /// is not evidence about the server. Returns the number of records released.
  Future<int> clearBackoff() async {
    _db.execute(
      "UPDATE records SET nextRetryAt = NULL "
      "WHERE syncState = 'pending' AND nextRetryAt IS NOT NULL;",
    );
    return _db.updatedRows;
  }

  /// Applies referral acknowledgements pulled from the PHC.
  ///
  /// Scoped to `syncState = 'synced'` so an ack can never resurrect or mutate a
  /// record the server has not actually accepted.
  Future<void> applyAcks(Iterable<ReferralAck> acks) async {
    if (acks.isEmpty) return;
    final stmt = _db.prepare('''
      UPDATE records
         SET referralState = ?, referralUpdatedAt = ?, referralUpdatedBy = ?,
             clinicianOutcome = COALESCE(?, clinicianOutcome),
             clinicianOutcomeAt = COALESCE(?, clinicianOutcomeAt)
       WHERE recordId = ? AND syncState = 'synced';
    ''');
    try {
      for (final a in acks) {
        // COALESCE, not a plain assignment: an ack that carries only a referral
        // state must not erase an outcome a clinician already recorded. Acks
        // arrive repeatedly and out of order, and a finding is the one thing
        // here that cannot be reconstructed from the device.
        stmt.execute([
          a.referralState.wire,
          a.referralUpdatedAt?.toIso8601String(),
          a.referralUpdatedBy,
          a.clinicianOutcome?.wire,
          a.clinicianOutcomeAt?.toIso8601String(),
          a.recordId,
        ]);
      }
    } finally {
      stmt.dispose();
    }
  }

  /// Drops old synced records so a shared phone does not grow without bound.
  ///
  /// `pending` and `failed` are never pruned, at any age. An unsynced referral
  /// is the one thing on this device that cannot be reconstructed from
  /// anywhere else.
  Future<int> pruneSynced({Duration olderThan = const Duration(days: 30)}) async {
    final cutoff = DateTime.now().subtract(olderThan).toIso8601String();
    _db.execute(
      "DELETE FROM records WHERE syncState = 'synced' AND capturedAt < ?;",
      [cutoff],
    );
    return _db.updatedRows;
  }

  // ---- Reads --------------------------------------------------------------

  /// Records due for an upload attempt, oldest first.
  ///
  /// Oldest first because a referral that has been waiting longest is the one
  /// most likely to matter, and because it makes the queue drain in a order a
  /// human can predict when watching it on stage.
  Future<List<ScreeningRecord>> nextBatch({
    int limit = 25,
    DateTime? now,
  }) async {
    final t = (now ?? DateTime.now()).toIso8601String();
    final rows = _db.select(
      '''
      SELECT * FROM records
       WHERE syncState = 'pending'
         AND (nextRetryAt IS NULL OR nextRetryAt <= ?)
       ORDER BY capturedAt ASC
       LIMIT ?;
      ''',
      [t, limit],
    );
    return rows.map(_fromRow).toList();
  }

  Future<int> pendingCount() async => _db
      .select("SELECT COUNT(*) AS c FROM records WHERE syncState = 'pending';")
      .first['c'] as int;

  /// Drives the pending badge on the capture screen.
  ///
  /// The visible count is what makes the offline claim concrete: a demo
  /// audience watching "3 waiting to upload" become "0" the moment wifi comes
  /// on has seen the whole architecture in one number.
  ///
  /// Emits the current value immediately on listen, then after every write.
  Stream<int> watchPendingCount() async* {
    yield await pendingCount();
    yield* _pending.stream;
  }

  Future<ScreeningRecord?> byId(String recordId) async {
    final rows =
        _db.select('SELECT * FROM records WHERE recordId = ?;', [recordId]);
    return rows.isEmpty ? null : _fromRow(rows.first);
  }

  /// Every screening for one patient, newest first.
  ///
  /// The WHV needs this at the doorstep with no network, which is why it is a
  /// local query rather than a server call: the longitudinal advantage in
  /// docs/PRODUCT.md section 3 is worth nothing if it only exists where there
  /// is coverage. Backed by idx_patient_time.
  Future<List<ScreeningRecord>> forPatient(String patientPseudoId) async =>
      _db
          .select(
            'SELECT * FROM records WHERE patientPseudoId = ? '
            'ORDER BY capturedAt DESC;',
            [patientPseudoId],
          )
          .map(_fromRow)
          .toList();

  /// Distinct patients this device has ever screened.
  Future<List<String>> knownPatients() async => _db
      .select('SELECT DISTINCT patientPseudoId FROM records '
          'ORDER BY patientPseudoId;')
      .map((r) => r['patientPseudoId'] as String)
      .toList();

  Future<List<ScreeningRecord>> all() async =>
      _db.select('SELECT * FROM records ORDER BY capturedAt ASC;')
          .map(_fromRow)
          .toList();

  Future<int> attemptCount(String recordId) async {
    final rows = _db.select(
        'SELECT attemptCount FROM records WHERE recordId = ?;', [recordId]);
    return rows.isEmpty ? 0 : rows.first['attemptCount'] as int;
  }

  void _emitPending() {
    if (_pending.isClosed) return;
    pendingCount().then((c) {
      if (!_pending.isClosed) _pending.add(c);
    });
  }

  Future<void> close() async {
    await _pending.close();
    _db.dispose();
  }

  static int _b(Object? v) => (v == true) ? 1 : 0;

  static ScreeningRecord _fromRow(Row r) => ScreeningRecord.fromJson({
        'recordId': r['recordId'],
        'patientPseudoId': r['patientPseudoId'],
        'whvId': r['whvId'],
        'phcId': r['phcId'],
        'capturedAt': r['capturedAt'],
        'lat': r['lat'],
        'lon': r['lon'],
        'locationAccuracyM': r['locationAccuracyM'],
        'ppgResult': r['ppgResult'],
        'ppgMeanHr': r['ppgMeanHr'],
        'ppgIrregularityScore': r['ppgIrregularityScore'],
        'ppgPerfusionIndex': r['ppgPerfusionIndex'],
        'ecgDurationSec': r['ecgDurationSec'],
        'sqiScore': r['sqiScore'],
        'motionRejected': r['motionRejected'] == 1,
        'leadOffDetected': r['leadOffDetected'] == 1,
        'meanHr': r['meanHr'],
        'rrIntervalCount': r['rrIntervalCount'],
        'rrIrregularityScore': r['rrIrregularityScore'],
        'cnnScore': r['cnnScore'],
        'decidedBy': r['decidedBy'],
        'pulseDeficitBpm': r['pulseDeficitBpm'],
        'perfusedBeatFraction': r['perfusedBeatFraction'],
        'nonPerfusingBeats': r['nonPerfusingBeats'],
        'medianPttMs': r['medianPttMs'],
        'fusionValid': r['fusionValid'] == 1,
        'fusionImplausible': r['fusionImplausible'] == 1,
        'ppgCorroboration': r['ppgCorroboration'],
        'tier': r['tier'],
        'modelVersion': r['modelVersion'],
        'ecgWaveformRef': r['ecgWaveformRef'],
        'syncState': r['syncState'],
        'syncedAt': r['syncedAt'],
        'referralState': r['referralState'],
        'referralUpdatedAt': r['referralUpdatedAt'],
        'referralUpdatedBy': r['referralUpdatedBy'],
        'clinicianOutcome': r['clinicianOutcome'],
        'clinicianOutcomeAt': r['clinicianOutcomeAt'],
      });
}
