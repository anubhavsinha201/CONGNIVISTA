import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart';

import 'package:arogyax/data/local_store.dart';
import 'package:arogyax/data/record.dart';
import 'package:arogyax/data/store_factory.dart';

/// The queue itself: what survives, what is visible, and what the migration
/// does to records captured before the v2 amendment.

String id(int n) => '3f2504e0-4f89-41d3-9a0c-${n.toString().padLeft(12, '0')}';

ScreeningRecord record(
  int n, {
  String tier = 'GREEN',
  int minute = 0,
  bool fusion = false,
}) =>
    ScreeningRecord(
      recordId: id(n),
      patientPseudoId: 'a1b2c3d4e5f60718',
      whvId: 'whv-021',
      phcId: 'phc-042',
      capturedAt: DateTime(2026, 8, 29, 9, minute),
      lat: 11.0168,
      lon: 76.9558,
      ecgDurationSec: 30,
      sqiScore: 0.8,
      motionRejected: false,
      leadOffDetected: false,
      meanHr: 74,
      rrIntervalCount: 37,
      rrIrregularityScore: 0.11,
      decidedBy: 'rules',
      pulseDeficitBpm: fusion ? 11.2 : null,
      perfusedBeatFraction: fusion ? 0.72 : null,
      nonPerfusingBeats: fusion ? 9 : null,
      medianPttMs: fusion ? 268 : null,
      fusionValid: fusion,
      ppgCorroboration: fusion ? 'pulseDeficit' : 'none',
      tier: tier,
      modelVersion: 'rules-1.0',
    );

void main() {
  late LocalStore store;

  setUp(() => store = StoreFactory.inMemory());
  tearDown(() => store.close());

  group('queueing', () {
    test('a capture lands as pending', () async {
      await store.insert(record(0));

      expect(await store.pendingCount(), 1);
      expect((await store.byId(id(0)))!.syncState, SyncState.pending);
    });

    test('every field survives the round trip through sqlite', () async {
      final original = record(0, tier: 'RED', fusion: true);
      await store.insert(original);

      expect((await store.byId(id(0)))!.toJson(), original.toJson());
    });

    test('an invalid record is refused rather than queued', () async {
      final bad = ScreeningRecord(
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

      expect(() => store.insert(bad), throwsArgumentError);
      expect(await store.pendingCount(), 0);
    });

    test('re-inserting the same recordId does not duplicate', () async {
      await store.insert(record(0));
      await store.insert(record(0));

      expect((await store.all()).length, 1);
    });
  });

  group('the visible pending count', () {
    test('emits the current value on listen', () async {
      await store.insert(record(0));
      await store.insert(record(1));

      expect(await store.watchPendingCount().first, 2);
    });

    test('falls as records sync', () async {
      await store.insert(record(0));
      await store.insert(record(1));
      await store.insert(record(2));

      final seen = <int>[];
      final sub = store.watchPendingCount().listen(seen.add);

      await store.markSynced([id(0), id(1)], DateTime.now());
      await Future<void>.delayed(const Duration(milliseconds: 20));
      await sub.cancel();

      expect(seen.first, 3);
      expect(seen.last, 1);
    });
  });

  group('nextBatch', () {
    test('returns oldest first', () async {
      for (final n in [2, 0, 1]) {
        await store.insert(record(n, minute: n));
      }

      expect(
        (await store.nextBatch()).map((r) => r.recordId),
        [id(0), id(1), id(2)],
      );
    });

    test('honours the limit', () async {
      for (var i = 0; i < 10; i++) {
        await store.insert(record(i, minute: i));
      }

      expect(await store.nextBatch(limit: 4), hasLength(4));
    });

    test('excludes synced and failed records', () async {
      await store.insert(record(0, minute: 0));
      await store.insert(record(1, minute: 1));
      await store.insert(record(2, minute: 2));

      await store.markSynced([id(0)], DateTime.now());
      await store.markFailed(id(1));

      expect((await store.nextBatch()).map((r) => r.recordId), [id(2)]);
    });

    test('excludes records still inside their backoff window', () async {
      final now = DateTime(2026, 8, 29, 10);
      await store.insert(record(0));
      await store.markRetryable(id(0), now.add(const Duration(minutes: 5)));

      expect(await store.nextBatch(now: now), isEmpty);
      expect(await store.nextBatch(now: now.add(const Duration(minutes: 6))),
          hasLength(1));
    });
  });

  group('clearBackoff', () {
    test('releases every pending record at once', () async {
      final now = DateTime(2026, 8, 29, 10);
      await store.insert(record(0, minute: 0));
      await store.insert(record(1, minute: 1));
      await store.markRetryable(id(0), now.add(const Duration(hours: 1)));
      await store.markRetryable(id(1), now.add(const Duration(hours: 1)));

      expect(await store.nextBatch(now: now), isEmpty);
      await store.clearBackoff();
      expect(await store.nextBatch(now: now), hasLength(2));
    });

    test('does not resurrect a failed record', () async {
      await store.insert(record(0));
      await store.markFailed(id(0));

      await store.clearBackoff();

      expect(await store.nextBatch(now: DateTime(2027)), isEmpty);
    });
  });

  group('acknowledgements', () {
    test('apply to a synced record', () async {
      await store.insert(record(0, tier: 'RED'));
      await store.markSynced([id(0)], DateTime.now());

      await store.applyAcks([
        ReferralAck(
          recordId: id(0),
          referralState: ReferralState.visitScheduled,
          referralUpdatedBy: 'phc-042',
        )
      ]);

      final r = await store.byId(id(0));
      expect(r!.referralState, ReferralState.visitScheduled);
      expect(r.referralUpdatedBy, 'phc-042');
    });

    test('cannot touch a record the server never accepted', () async {
      await store.insert(record(0));

      await store.applyAcks([
        ReferralAck(recordId: id(0), referralState: ReferralState.closed)
      ]);

      expect((await store.byId(id(0)))!.referralState, isNull);
    });
  });

  group('pruning', () {
    test('drops old synced records', () async {
      await store.insert(record(0));
      await store.markSynced([id(0)], DateTime.now());

      expect(await store.pruneSynced(olderThan: Duration.zero), 1);
      expect(await store.byId(id(0)), isNull);
    });

    test('never drops an unsynced referral, however old', () async {
      // The one thing on this device that cannot be reconstructed.
      await store.insert(record(0));
      await store.insert(record(1));
      await store.markFailed(id(1));

      expect(await store.pruneSynced(olderThan: Duration.zero), 0);
      expect((await store.all()).length, 2);
    });
  });

  group('migration', () {
    test('a fresh database opens at the current version', () {
      final db = sqlite3.openInMemory();
      LocalStore(db);

      expect(db.select('PRAGMA user_version;').first.values.first,
          LocalStore.kSchemaVersion);
      db.dispose();
    });

    test('is idempotent — reopening the same database is a no-op', () async {
      final db = sqlite3.openInMemory();
      final first = LocalStore(db);
      await first.insert(record(0));

      // Reopening must not throw on an ALTER for a column that already exists.
      final second = LocalStore(db);

      expect((await second.all()).length, 1);
      db.dispose();
    });

    test('v1 rows survive the upgrade with their referrals intact', () async {
      final db = sqlite3.openInMemory();

      // A v1 database, as a phone in the field would already have.
      LocalStore(db);
      db.execute('PRAGMA user_version = 1;');
      for (final col in const [
        'ppgIrregularityScore', 'ppgPerfusionIndex', 'pulseDeficitBpm',
        'perfusedBeatFraction', 'nonPerfusingBeats', 'medianPttMs',
        'fusionValid', 'fusionImplausible', 'ppgCorroboration',
        'referralState', 'referralUpdatedAt', 'referralUpdatedBy',
      ]) {
        db.execute('ALTER TABLE records DROP COLUMN $col;');
      }
      db.execute(
        "INSERT INTO records (recordId, patientPseudoId, whvId, capturedAt, "
        "ecgDurationSec, sqiScore, motionRejected, leadOffDetected, decidedBy, "
        "tier, modelVersion, syncState, attemptCount) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?);",
        [
          id(0), 'a1b2c3d4e5f60718', 'whv-021', '2026-08-20T09:00:00+05:30',
          30.0, 0.8, 0, 0, 'rules', 'RED', 'rules-1.0', 'pending', 0,
        ],
      );

      final upgraded = LocalStore(db);

      expect(db.select('PRAGMA user_version;').first.values.first, 2);
      final r = await upgraded.byId(id(0));
      expect(r, isNotNull, reason: 'a v1 referral was lost in the migration');
      expect(r!.tier, 'RED');
      expect(r.syncState, SyncState.pending);
      // New columns default rather than corrupting the row.
      expect(r.fusionValid, isFalse);
      expect(r.pulseDeficitBpm, isNull);
      expect(r.referralState, isNull);

      db.dispose();
    });
  });
}
