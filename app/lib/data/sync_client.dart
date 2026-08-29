import 'dart:convert';

import 'package:http/http.dart' as http;

import 'record.dart';

/// Per-record outcome of an upload. See contracts/sync.md section 5.
enum UploadStatus {
  /// Stored server-side. Only this marks a record synced.
  accepted,

  /// Malformed. Retrying cannot fix it, so it leaves the queue.
  rejected,

  /// Server-side transient. Stays pending, backs off.
  retryable,
}

class UploadResult {
  final String recordId;
  final UploadStatus status;
  final String? code;

  const UploadResult(this.recordId, this.status, {this.code});
}

/// Transport for the sync service.
///
/// Kept behind an interface so [SyncEngine] can be tested against a fake with
/// no HTTP, no server and no timing. The engine holds the retry policy and the
/// state machine — the parts worth testing exhaustively — and this holds only
/// the wire format.
abstract class SyncClient {
  /// At most 25 records, oldest first.
  ///
  /// Must return one [UploadResult] per record. A record the server did not
  /// mention is the caller's problem to default, and [SyncEngine] defaults it
  /// to [UploadStatus.retryable].
  Future<List<UploadResult>> uploadBatch(List<ScreeningRecord> records);

  /// Referral state changes since [since]. Returns the acks and the cursor to
  /// pass as the next `since`.
  Future<AckPage> fetchAcks({DateTime? since});
}

class AckPage {
  final List<ReferralAck> acks;
  final DateTime? cursor;
  const AckPage(this.acks, this.cursor);

  static const empty = AckPage(<ReferralAck>[], null);
}

/// Raised for transport-level failure — no radio, DNS, TLS, timeout, 5xx.
///
/// Distinct from a per-record `rejected`: this says nothing about the records
/// themselves, so every record in the batch stays pending.
class SyncTransportException implements Exception {
  final String message;
  final int? statusCode;
  const SyncTransportException(this.message, {this.statusCode});
  @override
  String toString() => 'SyncTransportException($statusCode): $message';
}

/// Raised when the device token is missing, revoked or attributed to another
/// worker. Not retryable by backing off — it needs re-provisioning.
class SyncAuthException implements Exception {
  final String message;
  const SyncAuthException(this.message);
  @override
  String toString() => 'SyncAuthException: $message';
}

class HttpSyncClient implements SyncClient {
  final Uri baseUrl;
  final String deviceToken;
  final http.Client _http;
  final Duration timeout;

  HttpSyncClient({
    required this.baseUrl,
    required this.deviceToken,

    /// Short on purpose. The realistic failure is not a slow server, it is a
    /// tower that completes a TCP handshake and then goes nowhere as the
    /// worker's bus moves. Hanging for 30 s on that burns the whole window of
    /// coverage the flush had to work with.
    this.timeout = const Duration(seconds: 12),
    http.Client? httpClient,
  }) : _http = httpClient ?? http.Client();

  Map<String, String> get _headers => {
        'Authorization': 'Bearer $deviceToken',
        'Content-Type': 'application/json',
      };

  @override
  Future<List<UploadResult>> uploadBatch(List<ScreeningRecord> records) async {
    if (records.isEmpty) return const [];

    final res = await _send(
      () => _http.post(
        baseUrl.resolve('v1/records:batch'),
        headers: _headers,
        body: jsonEncode({
          'records': [for (final r in records) r.toSyncJson()],
        }),
      ),
    );

    if (res.statusCode == 401 || res.statusCode == 403) {
      throw SyncAuthException('device token rejected (${res.statusCode})');
    }
    if (res.statusCode != 200) {
      throw SyncTransportException('batch upload failed',
          statusCode: res.statusCode);
    }

    final body = jsonDecode(res.body) as Map<String, dynamic>;
    final results = (body['results'] as List?) ?? const [];
    return [
      for (final r in results.cast<Map<String, dynamic>>())
        UploadResult(
          r['recordId'] as String,
          switch (r['status']) {
            'accepted' => UploadStatus.accepted,
            'rejected' => UploadStatus.rejected,
            _ => UploadStatus.retryable,
          },
          code: r['code'] as String?,
        ),
    ];
  }

  @override
  Future<AckPage> fetchAcks({DateTime? since}) async {
    // whvId is deliberately NOT a parameter. The server derives it from the
    // token, so a device cannot ask for another worker's referrals by editing
    // a query string.
    final uri = baseUrl.resolve('v1/acks').replace(queryParameters: {
      if (since != null) 'since': since.toIso8601String(),
    });

    final res = await _send(() => _http.get(uri, headers: _headers));

    if (res.statusCode == 401 || res.statusCode == 403) {
      throw SyncAuthException('device token rejected (${res.statusCode})');
    }
    if (res.statusCode != 200) {
      throw SyncTransportException('ack fetch failed',
          statusCode: res.statusCode);
    }

    final body = jsonDecode(res.body) as Map<String, dynamic>;
    final acks = [
      for (final a in ((body['acks'] as List?) ?? const [])
          .cast<Map<String, dynamic>>())
        ReferralAck.fromJson(a),
    ];
    final cursor = body['cursor'] == null
        ? null
        : DateTime.parse(body['cursor'] as String);
    return AckPage(acks, cursor);
  }

  Future<http.Response> _send(Future<http.Response> Function() f) async {
    try {
      return await f().timeout(timeout);
    } on SyncAuthException {
      rethrow;
    } catch (e) {
      // Everything below HTTP — socket, DNS, TLS, timeout — is one thing to
      // the caller: the network did not work. The distinction that matters is
      // transport-vs-record, and that is preserved.
      throw SyncTransportException('$e');
    }
  }

  void close() => _http.close();
}
