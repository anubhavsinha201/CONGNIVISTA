import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';

import 'local_store.dart';
import 'sync.dart';

/// Decides *when* [SyncEngine] runs. The engine decides what happens when it
/// does; keeping the two apart means the retry state machine is testable
/// without a platform channel anywhere near it.
///
/// Triggers, per contracts/sync.md section 8:
///   1. connectivity transitions to online
///   2. app foreground
///   3. the worker taps "sync now"
///   4. a periodic timer, only while pending > 0
class SyncScheduler {
  final SyncEngine engine;
  final LocalStore store;
  final Duration period;

  final _reports = StreamController<SyncReport>.broadcast();
  StreamSubscription<List<ConnectivityResult>>? _conn;
  Timer? _timer;
  bool _wasOnline = false;

  SyncScheduler({
    required this.engine,
    required this.store,
    this.period = const Duration(minutes: 5),
  });

  /// Every flush outcome, for a status line in the app.
  Stream<SyncReport> get reports => _reports.stream;

  void start({Stream<List<ConnectivityResult>>? connectivity}) {
    final source =
        connectivity ?? Connectivity().onConnectivityChanged;

    _conn = source.listen((results) {
      final online = results.any((r) => r != ConnectivityResult.none);

      // Only on the transition. Android emits this stream generously — every
      // wifi scan, every cell handover — and flushing on each one would keep a
      // radio awake that a health worker's phone needs to keep for the shift.
      if (online && !_wasOnline) {
        unawaited(_run(clearBackoff: true));
      }
      _wasOnline = online;
    });

    _timer = Timer.periodic(period, (_) async {
      if (await store.pendingCount() > 0) await _run();
    });
  }

  /// Call from `AppLifecycleState.resumed`.
  Future<void> onForeground() => _run();

  /// The worker's explicit "sync now". Clears backoff, because a person
  /// standing there having tapped a button is a stronger signal about
  /// connectivity than any ladder position.
  Future<void> syncNow() => _run(clearBackoff: true);

  Future<void> _run({bool clearBackoff = false}) async {
    try {
      final report =
          clearBackoff ? await engine.flushNow() : await engine.flush();
      if (!_reports.isClosed) _reports.add(report);
    } catch (e) {
      // A scheduler that throws would take the app down with it on a timer
      // tick, from a code path that by definition is not on the critical path
      // of care. Report and carry on.
      if (!_reports.isClosed) _reports.add(SyncReport(error: e));
    }
  }

  Future<void> stop() async {
    _timer?.cancel();
    await _conn?.cancel();
    await _reports.close();
  }
}
