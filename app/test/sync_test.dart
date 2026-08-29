import 'dart:math';

import 'package:flutter_test/flutter_test.dart';

import 'package:arogyax/data/local_store.dart';
import 'package:arogyax/data/record.dart';
import 'package:arogyax/data/store_factory.dart';
import 'package:arogyax/data/sync.dart';
import 'package:arogyax/data/sync_client.dart';

/// The retry state machine, against a fake transport.
///
/// The scenario worth designing for is not "the server is down" — it is a
/// worker riding out of a village and catching eight seconds of signal on a
/// ridge. Half a batch lands, the rest does not, and the next flush has to pick
/// up exactly where it stopped without duplicating a referral. Most of these
/// tests are that case in different disguises.
///
/// Mirrored in ml/reference/validate_record.py sections 6 and 7.

/// A programmable SyncClient. Each entry in [plan] answers one uploadBatch call.
class FakeClient implements SyncClient {
  /// recordId -> status, per call. Null means "throw a transport error".
  final List<Map<String, UploadStatus>?> plan;

  /// Default for records not named in the current plan entry.
  final UploadStatus fallback;

  final List<List<String>> sentBatches = [];
  int ackCalls = 0;
  List<ReferralAck> acksToReturn = const [];
  DateTime? cursorToReturn;
  bool authFails = false;
  bool ackFails = false;

  FakeClient({this.plan = const [], this.fallback = UploadStatus.accepted});

  int _call = 0;

  @override
  Future<List<UploadResult>> uploadBatch(List<ScreeningRecord> records) async {
    sentBatches.add([for (final r in records) r.recordId]);
    if (authFails) throw const SyncAuthException('revoked');

    final entry = _call < plan.length ? plan[_call] : const <String, UploadStatus>{};
    _call++;

    if (entry == null) throw const SyncTransportException('no route to host');

    return [
      for (final r in records)
        UploadResult(r.recordId, entry[r.recordId] ?? fallback),
    ];
  }

  @override
  Future<AckPage> fetchAcks({DateTime? since}) async {
    ackCalls++;
    if (ackFails) throw const SyncTransportException('no route to host');
    return AckPage(acksToReturn, cursorToReturn);
  }
}

ScreeningRecord record(String id, {String tier = 'GREEN', int minute = 0}) =>
    ScreeningRecord(
      recordId: id,
      patientPseudoId: 'a1b2c3d4e5f60718',
      whvId: 'whv-021',
      phcId: 'phc-042',
      capturedAt: DateTime(2026, 8, 29, 9, minute),
      ecgDurationSec: 30,
      sqiScore: 0.8,
      motionRejected: false,
      leadOffDetected: false,
      decidedBy: 'rules',
      tier: tier,
      modelVersion: 'rules-1.0',
    );

String id(int n) => '3f2504e0-4f89-41d3-9a0c-${n.toString().padLeft(12, '0')}';

void main() {
  late LocalStore store;
  final now = DateTime(2026, 8, 29, 9, 30);

  setUp(() => store = StoreFactory.inMemory());
  tearDown(() => store.close());

  Future<void> seed(int n) async {
    for (var i = 0; i < n; i++) {
      await store.insert(record(id(i), minute: i));
    }
  }

  SyncEngine engine(FakeClient c, {DateTime? at}) => SyncEngine(
        store: store,
        client: c,
        now: () => at ?? now,
        // Fixed so backoff deadlines are exact; jitter is tested separately.
        random: Random(1),
      );

  group('the happy path', () {
    test('a full flush empties the queue', () async {
      await seed(3);
      final c = FakeClient();

      final report = await engine(c).flush();

      expect(report.accepted, 3);
      expect(await store.pendingCount(), 0);
    });

    test('a record is only marked synced on its own result', () async {
      await seed(2);
      // The server returns 200 but mentions neither record. Silence is not an
      // acknowledgement: marking these synced would strand two referrals that
      // no PHC will ever see.
      final c = FakeClient(plan: [<String, UploadStatus>{}], fallback: UploadStatus.retryable);

      final report = await engine(c).flush();

      expect(report.accepted, 0);
      expect(await store.pendingCount(), 2);
    });

    test('batches are capped at the contract size', () async {
      await seed(60);
      final c = FakeClient();

      await engine(c).flush();

      expect(c.sentBatches.first.length, SyncEngine.kBatchSize);
      expect(await store.pendingCount(), 0);
    });

    test('oldest records go first', () async {
      await seed(5);
      final c = FakeClient();

      await engine(c).flush();

      expect(c.sentBatches.first, [id(0), id(1), id(2), id(3), id(4)]);
    });
  });

  group('the ridge with eight seconds of signal', () {
    test('a partially acked batch keeps the rest pending', () async {
      await seed(5);
      final c = FakeClient(plan: [
        {
          id(0): UploadStatus.accepted,
          id(1): UploadStatus.accepted,
          id(2): UploadStatus.retryable,
          id(3): UploadStatus.retryable,
          id(4): UploadStatus.retryable,
        }
      ]);

      final report = await engine(c).flush();

      expect(report.accepted, 2);
      expect(report.retryable, 3);
      expect(await store.pendingCount(), 3);

      final synced = await store.byId(id(0));
      expect(synced!.syncState, SyncState.synced);
      expect(synced.syncedAt, isNotNull);
    });

    test('the next flush resumes rather than restarting', () async {
      await seed(5);
      final c = FakeClient(plan: [
        {
          id(0): UploadStatus.accepted,
          id(1): UploadStatus.accepted,
          id(2): UploadStatus.retryable,
          id(3): UploadStatus.retryable,
          id(4): UploadStatus.retryable,
        },
        <String, UploadStatus>{}, // second flush: everything accepted
      ]);

      final e = engine(c);
      await e.flush();
      await e.flushNow();

      expect(await store.pendingCount(), 0);
      // The already-synced pair is never sent again — the scarce resource here
      // is the coverage window, not server capacity.
      expect(c.sentBatches[1], [id(2), id(3), id(4)]);
    });

    test('a transport failure loses nothing', () async {
      await seed(4);
      final c = FakeClient(plan: [null]);

      final report = await engine(c).flush();

      expect(report.error, isA<SyncTransportException>());
      expect(await store.pendingCount(), 4);
      for (var i = 0; i < 4; i++) {
        expect((await store.byId(id(i)))!.syncState, SyncState.pending);
      }
    });
  });

  group('rejections', () {
    test('a rejected record leaves the queue and is not retried', () async {
      await seed(2);
      final c = FakeClient(plan: [
        {id(0): UploadStatus.rejected, id(1): UploadStatus.accepted},
      ]);

      final report = await engine(c).flush();

      expect(report.rejected, 1);
      expect((await store.byId(id(0)))!.syncState, SyncState.failed);
      expect(await store.pendingCount(), 0);
      expect(await store.nextBatch(now: now.add(const Duration(days: 7))), isEmpty);
    });

    test('a rejected record is kept on the device, not deleted', () async {
      // A record the server will not take is a bug worth being able to look
      // at, not something to discard quietly.
      await seed(1);
      await engine(FakeClient(plan: [{id(0): UploadStatus.rejected}])).flush();

      expect(await store.byId(id(0)), isNotNull);
      expect((await store.all()).length, 1);
    });
  });

  group('backoff', () {
    test('climbs the ladder and caps at an hour', () async {
      final e = engine(FakeClient());
      final steps = [
        for (var attempts = 0; attempts < 8; attempts++)
          e.nextRetryAt(attempts).difference(now).inMilliseconds
      ];

      for (var i = 0; i < steps.length; i++) {
        final base = SyncEngine
            .kBackoff[min(i, SyncEngine.kBackoff.length - 1)].inMilliseconds;
        expect(steps[i], greaterThanOrEqualTo((base * 0.5).round()));
        expect(steps[i], lessThanOrEqualTo((base * 1.5).round()));
      }
      expect(steps[7], lessThanOrEqualTo(const Duration(hours: 1).inMilliseconds * 1.5));
    });

    test('a backed-off record is not retried before its deadline', () async {
      await seed(1);
      await engine(FakeClient(plan: [{id(0): UploadStatus.retryable}])).flush();

      expect(await store.nextBatch(now: now), isEmpty);
      expect(await store.nextBatch(now: now.add(const Duration(seconds: 10))),
          hasLength(1));
    });

    test('the attempt counter climbs across flushes', () async {
      await seed(1);
      final c = FakeClient(plan: [
        {id(0): UploadStatus.retryable},
        {id(0): UploadStatus.retryable},
      ]);
      final e = engine(c);

      await e.flush();
      await e.flushNow();

      expect(await store.attemptCount(id(0)), 2);
    });

    test('jitter spreads simultaneous retries', () async {
      // Four workers in one van hit the same tower at the same second. Without
      // jitter their phones retry in lockstep and keep colliding on every rung.
      final deadlines = {
        for (var seed = 0; seed < 40; seed++)
          SyncEngine(
            store: store,
            client: FakeClient(),
            now: () => now,
            random: Random(seed),
          ).nextRetryAt(3).millisecondsSinceEpoch
      };
      expect(deadlines.length, greaterThan(30));
    });

    test('walking back into coverage clears the backoff', () async {
      await seed(3);
      final c = FakeClient(plan: [
        {id(0): UploadStatus.retryable, id(1): UploadStatus.retryable, id(2): UploadStatus.retryable},
        <String, UploadStatus>{},
      ]);
      final e = engine(c);

      await e.flush();
      expect(await store.nextBatch(now: now), isEmpty, reason: 'all backing off');

      // The ladder exists for a server that is refusing, not for an absent
      // radio. A connectivity event is new information.
      final report = await e.flushNow();

      expect(report.accepted, 3);
      expect(await store.pendingCount(), 0);
    });

    test('clearing backoff does not reset the ladder position', () async {
      await seed(1);
      final e = engine(FakeClient(plan: [{id(0): UploadStatus.retryable}]));
      await e.flush();

      await store.clearBackoff();

      expect(await store.attemptCount(id(0)), 1);
    });
  });

  group('idempotency', () {
    test('an accepted record is never uploaded twice', () async {
      await seed(3);
      final c = FakeClient();
      final e = engine(c);

      await e.flush();
      await e.flush();
      await e.flushNow();

      final everySent = c.sentBatches.expand((b) => b).toList();
      expect(everySent.toSet().length, everySent.length,
          reason: 'a record was uploaded twice: $everySent');
    });
  });

  group('auth', () {
    test('a revoked token stops the flush without failing records', () async {
      // Backing off will not fix a revoked token, and marking records failed
      // would discard referrals over what is a provisioning problem.
      await seed(3);
      final c = FakeClient()..authFails = true;

      final report = await engine(c).flush();

      expect(report.error, isA<SyncAuthException>());
      expect(await store.pendingCount(), 3);
      expect(await store.attemptCount(id(0)), 0);
    });
  });

  group('acknowledgements', () {
    test('an ack applies to a synced record', () async {
      await seed(1);
      final c = FakeClient()
        ..acksToReturn = [
          ReferralAck(
            recordId: id(0),
            referralState: ReferralState.seenAtPhc,
            referralUpdatedAt: now,
            referralUpdatedBy: 'phc-042',
          )
        ]
        ..cursorToReturn = now;

      final report = await engine(c).flush();

      expect(report.acksApplied, 1);
      expect((await store.byId(id(0)))!.referralState, ReferralState.seenAtPhc);
    });

    test('an ack cannot touch a record the server never accepted', () async {
      await seed(1);
      final c = FakeClient(plan: [{id(0): UploadStatus.retryable}])
        ..acksToReturn = [
          ReferralAck(recordId: id(0), referralState: ReferralState.closed)
        ];

      await engine(c).flush();

      expect((await store.byId(id(0)))!.referralState, isNull);
    });

    test('a failed ack pull does not fail a flush that uploaded', () async {
      // Acks are a convenience for the worker; referrals are the product.
      await seed(2);
      final c = FakeClient()..ackFails = true;

      final report = await engine(c).flush();

      expect(report.accepted, 2);
      expect(report.acksApplied, 0);
      expect(await store.pendingCount(), 0);
    });

    test('the cursor only advances on a successful pull', () async {
      await seed(1);
      final c = FakeClient()..ackFails = true;
      final e = engine(c);

      await e.flush();

      // A cursor advanced past acks that were never applied loses them for good.
      expect(e.ackCursor, isNull);
    });
  });

  group('re-entrancy', () {
    test('overlapping triggers do not run two flushes at once', () async {
      // Connectivity, foreground, timer and a worker's tap can all fire inside
      // one second of a radio coming up.
      await seed(3);
      final c = FakeClient();
      final e = engine(c);

      await Future.wait([e.flush(), e.flush(), e.flush()]);

      final everySent = c.sentBatches.expand((b) => b).toList();
      expect(everySent.toSet().length, everySent.length);
    });
  });
}
