import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:sqlite3/sqlite3.dart';

import 'db_key.dart';
import 'local_store.dart';

/// Opens the encrypted record queue.
///
/// Split out from [LocalStore] so the store itself has no dependency on
/// `path_provider`, the Keystore, or a real filesystem — which is what lets its
/// tests run against an in-memory database with no device attached.
class StoreFactory {
  static const String kDbFileName = 'arogyax_records.db';

  const StoreFactory._();

  /// Opens (creating on first run) the SQLCipher database and returns a store.
  ///
  /// Throws [MissingDbKey] if the file exists but the key does not — see the
  /// note on [DbKey.read] for why that must not be papered over.
  static Future<LocalStore> open({DbKey keys = const DbKey()}) async {
    final dir = await getApplicationDocumentsDirectory();
    final path = '${dir.path}${Platform.pathSeparator}$kDbFileName';
    final exists = File(path).existsSync();

    final key = await keys.read(databaseExists: exists);
    final db = sqlite3.open(path);

    // Must be the first statement on the connection. SQLCipher decrypts lazily,
    // so a wrong key does not fail here — it fails on the first read, with
    // "file is not a database".
    db.execute(DbKey.pragma(key));

    try {
      // Force a page read so a bad key surfaces here, next to the code that
      // supplied it, rather than three layers up inside a migration.
      db.select('SELECT count(*) FROM sqlite_master;');
    } catch (e) {
      db.dispose();
      throw MissingDbKey(
        'The record database could not be decrypted with the stored key ($e). '
        'Not recreating it — that would discard every unsynced referral.',
      );
    }

    // Survive a battery pull mid-write. The queue is the durability guarantee
    // the whole offline claim rests on; a torn write here costs referrals.
    db.execute('PRAGMA journal_mode = WAL;');
    db.execute('PRAGMA synchronous = FULL;');
    db.execute('PRAGMA foreign_keys = ON;');

    return LocalStore(db);
  }

  /// An unencrypted in-memory store, for tests only.
  static LocalStore inMemory() => LocalStore(sqlite3.openInMemory());
}
