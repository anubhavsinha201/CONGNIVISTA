import 'dart:async';
import 'dart:math';

import 'local_store.dart';
import 'record.dart';
import 'sync_client.dart';

/// Outcome of one flush, for the UI and for tests.
class SyncReport {
  final int accepted;
  final int rejected;
  final int retryable;
  final int acksApplied;
  final Object? error;

  const SyncReport({
    this.accepted = 0,
    this.rejected = 0,
    this.retryable = 0,
    this.acksApplied = 0,
    this.error,
  });

  bool get madeProgress => accepted > 0 || acksApplied > 0;

  @override
  String toString() => 'SyncReport(accepted: $accepted, rejected: $rejected, '
      'retryable: $retryable, acks: $acksApplied, error: $error)';
}

/// Opportunistic flush of the local queue to the PHC.
///
/// ## The rule this class exists to respect
///
/// **Sync never blocks a result.** Nothing here is on the path between a
/// patient and their tier. The capture screen calls `LocalStore.insert` and
/// returns; if this engine is broken, throwing, or not running at all, the
/// screening still completed and the record is still safe on disk. Every
/// method below can fail without a patient noticing.
///
/// See contracts/sync.md.
class SyncEngine {
  /// Backoff ladder, contracts/sync.md section 7. Per record, persisted.
  static const List<Duration> kBackoff = [
    Duration(seconds: 5),
    Duration(seconds: 30),
    Duration(minutes: 2),
    Duration(minutes: 10),
    Duration(minutes: 30),
    Duration(hours: 1),
  ];

  static const int kBatchSize = 25;

  final LocalStore store;
  final SyncClient client;

  /// Injected so the tests are deterministic.
  final DateTime Function() _now;
  final Random _rng;

  DateTime? _ackCursor;
  bool _running = false;

  SyncEngine({
    required this.store,
    required this.client,
    DateTime Function()? now,
    Random? random,
    DateTime? ackCursor,
  })  : _now = now ?? DateTime.now,
        _rng = random ?? Random(),
        _ackCursor = ackCursor;

  DateTime? get ackCursor => _ackCursor;

  /// True while a flush is in flight.
  bool get isRunning => _running;

  /// Drains the queue, then pulls acknowledgements.
  ///
  /// Re-entrant calls are dropped rather than queued: four triggers can fire
  /// within a second of a radio coming up (connectivity event, foreground,
  /// timer, and the worker tapping "sync now"), and running four concurrent
  /// flushes would upload the same batch four times. The upsert makes that
  /// harmless server-side, but it wastes the coverage window, which is the
  /// scarce resource here.
  Future<SyncReport> flush({int maxBatches = 20}) async {
    if (_running) return const SyncReport();
    _running = true;
    try {
      return await _flush(maxBatches);
    } finally {
      _running = false;
    }
  }

  Future<SyncReport> _flush(int maxBatches) async {
    var accepted = 0, rejected = 0, retryable = 0;
    Object? error;

    for (var i = 0; i < maxBatches; i++) {
      final batch = await store.nextBatch(limit: kBatchSize, now: _now());
      if (batch.isEmpty) break;

      final List<UploadResult> results;
      try {
        results = await client.uploadBatch(batch);
      } on SyncAuthException catch (e) {
        // Backing off will not fix a revoked token. Stop, keep everything
        // pending, and surface it — the phone needs re-provisioning, and that
        // is a human action, not a retry.
        error = e;
        break;
      } on SyncTransportException catch (e) {
        // The network failed. This says nothing about the records, so the
        // whole batch stays pending and backs off together.
        error = e;
        for (final r in batch) {
          await _backOff(r.recordId, code: 'transport');
        }
        retryable += batch.length;
        break;
      }

      final byId = {for (final r in results) r.recordId: r};
      final synced = <String>[];
      var rejectedHere = 0;

      for (final r in batch) {
        // A record the server did not mention is retryable, never accepted.
        // Silence is not an acknowledgement: treating it as one would mark a
        // referral synced that no PHC will ever see.
        final res = byId[r.recordId] ??
            UploadResult(r.recordId, UploadStatus.retryable, code: 'no_result');

        switch (res.status) {
          case UploadStatus.accepted:
            synced.add(r.recordId);
            accepted++;
          case UploadStatus.rejected:
            await store.markFailed(r.recordId, errorCode: res.code);
            rejected++;
            rejectedHere++;
          case UploadStatus.retryable:
            await _backOff(r.recordId, code: res.code);
            retryable++;
        }
      }

      await store.markSynced(synced, _now());

      // Nothing in THIS batch moved: every row in it is now backing off, so the
      // next iteration would fetch the same rows and fail the same way. Stop.
      // Counted per batch, not cumulatively — a rejection three batches ago
      // must not keep this loop alive.
      if (synced.isEmpty && rejectedHere == 0) break;
    }

    final acks = await _pullAcks();

    return SyncReport(
      accepted: accepted,
      rejected: rejected,
      retryable: retryable,
      acksApplied: acks,
      error: error,
    );
  }

  Future<int> _pullAcks() async {
    try {
      final page = await client.fetchAcks(since: _ackCursor);
      if (page.acks.isNotEmpty) await store.applyAcks(page.acks);
      // Only advance the cursor on success. A cursor advanced past acks that
      // were never applied loses them permanently.
      if (page.cursor != null) _ackCursor = page.cursor;
      return page.acks.length;
    } catch (_) {
      // Acks are a convenience for the worker, not part of the referral path.
      // Failing to fetch them must not fail a flush that uploaded records.
      return 0;
    }
  }

  Future<void> _backOff(String recordId, {String? code}) async {
    final attempts = await store.attemptCount(recordId);
    await store.markRetryable(recordId, nextRetryAt(attempts), errorCode: code);
  }

  /// Next attempt time for a record that has already been tried [attempts]
  /// times, with jitter.
  ///
  /// The jitter is not cosmetic. A van carrying four workers home hits the same
  /// tower at the same second; without it their four phones retry in lockstep
  /// and keep colliding on every subsequent rung of the ladder.
  DateTime nextRetryAt(int attempts) {
    final base = kBackoff[min(attempts, kBackoff.length - 1)];
    final jitter = 0.5 + _rng.nextDouble(); // [0.5, 1.5)
    return _now().add(
      Duration(milliseconds: (base.inMilliseconds * jitter).round()),
    );
  }

  /// Clears every pending backoff and flushes immediately.
  ///
  /// For a connectivity transition and for the worker's manual "sync now". The
  /// ladder exists for a server that is refusing; it should not keep a record
  /// waiting 30 minutes when the radio has just come back and the previous
  /// failure was only ever an absent network.
  Future<SyncReport> flushNow() async {
    await store.clearBackoff();
    return flush();
  }
}
