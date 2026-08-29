import 'dart:convert';
import 'dart:math';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Manages the SQLCipher passphrase for the local record queue.
///
/// The queue holds pending referrals for whole villages. A phone left in an
/// autorickshaw is a realistic loss scenario for a doorstep scheme, and an
/// unencrypted sqlite file hands over every screening on it. The key lives in
/// `flutter_secure_storage`, which is the Android Keystore underneath — so it is
/// bound to the device and does not travel with a copied file.
class DbKey {
  static const _storageKey = 'arogyax.db.key.v1';

  final FlutterSecureStorage _storage;

  const DbKey({FlutterSecureStorage storage = const FlutterSecureStorage()})
      : _storage = storage;

  /// Reads the key, generating one on first launch.
  ///
  /// Throws [MissingDbKey] when a database already exists but its key does not.
  /// That combination means the Keystore entry was lost — an OS restore to a
  /// new device, or the user clearing app data through Settings.
  ///
  /// **The failure is loud on purpose.** Generating a fresh key here would open
  /// as a working, empty database, and a WHV would find her morning's referrals
  /// simply gone with no error to report. A crash with a message is recoverable
  /// by a human; silent data loss in a screening queue is not.
  Future<String> read({required bool databaseExists}) async {
    final existing = await _storage.read(key: _storageKey);
    if (existing != null && existing.isNotEmpty) return existing;

    if (databaseExists) {
      throw const MissingDbKey(
        'An encrypted record database exists but its key is gone from secure '
        'storage. Refusing to create a new key, which would silently discard '
        'every unsynced referral on this device.',
      );
    }

    final key = _generate();
    await _storage.write(key: _storageKey, value: key);
    return key;
  }

  /// 256 bits, hex-encoded.
  static String _generate() {
    final rng = Random.secure();
    final bytes = List<int>.generate(32, (_) => rng.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }

  /// Escapes the key for the `PRAGMA key` statement.
  ///
  /// The key is hex from [_generate] so this is belt-and-braces, but a
  /// provisioned or test key is a caller-supplied string reaching a statement
  /// that cannot take a bound parameter — `PRAGMA` does not accept one.
  static String pragma(String key) =>
      "PRAGMA key = '${key.replaceAll("'", "''")}';";

  /// Only for tests and for a deliberate, user-confirmed reset.
  Future<void> erase() => _storage.delete(key: _storageKey);
}

class MissingDbKey implements Exception {
  final String message;
  const MissingDbKey(this.message);
  @override
  String toString() => 'MissingDbKey: $message';
}

/// The deployment-wide salt for [PseudoId], and the per-device sync token.
///
/// Both are provisioned at worker login and both belong in the Keystore rather
/// than in shared preferences.
class DeviceSecrets {
  static const _saltKey = 'arogyax.deployment.salt';
  static const _tokenKey = 'arogyax.device.token';

  /// Demo fallback so the app runs before a provisioning flow exists.
  ///
  /// Records derived from this salt are not re-identifiable against a real MTM
  /// roll, which is correct — a demo build must not produce records that look
  /// like production ones.
  static const String kDemoSalt = 'arogyax-demo-salt-not-for-deployment';

  final FlutterSecureStorage _storage;

  const DeviceSecrets({
    FlutterSecureStorage storage = const FlutterSecureStorage(),
  }) : _storage = storage;

  Future<String> deploymentSalt() async =>
      await _storage.read(key: _saltKey) ?? kDemoSalt;

  Future<void> setDeploymentSalt(String salt) =>
      _storage.write(key: _saltKey, value: salt);

  /// Null until the device is provisioned. [SyncEngine] treats that as
  /// "nothing to do" rather than as an error: an unprovisioned phone still
  /// screens patients and still queues records.
  Future<String?> deviceToken() => _storage.read(key: _tokenKey);

  Future<void> setDeviceToken(String token) =>
      _storage.write(key: _tokenKey, value: token);
}

/// Base64 helper kept next to the key code so both uses stay in one place.
String b64(List<int> bytes) => base64Url.encode(bytes);
