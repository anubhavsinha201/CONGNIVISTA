import 'dart:convert';

import 'package:crypto/crypto.dart';

/// Derives [patientPseudoId] from an MTM patient identifier.
///
/// ## What this gives you, and what it does not
///
/// The salt is **deployment-wide** — one per PHC or district, not per device.
/// That is a deliberate choice with a real cost, so it is worth being precise
/// about both halves.
///
/// It has to be shared, because `record.schema.json` promises "the PHC
/// re-identifies from its own MTM roll". Re-identification is only possible if
/// the PHC can hash a roll entry and get the same value the phone produced. A
/// per-device random salt would be stronger privacy and would also make the
/// same patient screened by two workers look like two patients, and make the
/// referral queue unresolvable to an actual human being. That is not a
/// trade-off worth making for a referral system.
///
/// The cost: **the salt is only as secret as the APK.** Anyone holding both the
/// application binary and a copy of the MTM roll can re-identify every record,
/// because the identifier space is small enough to enumerate. So this defeats a
/// leaked database, a curious dashboard viewer, and casual analytics. It does
/// not defeat a determined attacker who already has the roll — and the roll is
/// the more sensitive artefact of the two.
///
/// Do not describe this as anonymisation. It is pseudonymisation, which is what
/// the schema says it is.
///
/// The raw MTM identifier is hashed at the call site and is never stored, never
/// logged, and never leaves the device (CLAUDE.md non-negotiable 5).
class PseudoId {
  /// Hex characters kept from the digest.
  ///
  /// 16 hex chars = 64 bits. The schema requires >= 8 characters; 64 bits keeps
  /// the chance of an accidental collision negligible across a state-sized roll
  /// while staying short enough to show on a referral card.
  static const int kLength = 16;

  const PseudoId._();

  /// HMAC-SHA256 over the normalised identifier, keyed by the deployment salt.
  ///
  /// HMAC rather than `sha256(salt + id)`: the plain-concatenation form is
  /// length-extendable and invites the salt being appended instead of
  /// prepended by whoever writes the PHC-side script. HMAC has one obvious way
  /// to use it, which is the property that matters when a second implementation
  /// has to match this one exactly.
  static String derive(String mtmPatientId, {required String deploymentSalt}) {
    if (deploymentSalt.isEmpty) {
      throw ArgumentError.value(
        deploymentSalt,
        'deploymentSalt',
        'refusing to derive a pseudo-ID with an empty salt',
      );
    }
    final normalised = normalise(mtmPatientId);
    if (normalised.isEmpty) {
      throw ArgumentError.value(
        mtmPatientId,
        'mtmPatientId',
        'empty after normalisation',
      );
    }

    final mac = Hmac(sha256, utf8.encode(deploymentSalt));
    final digest = mac.convert(utf8.encode(normalised));
    return digest.toString().substring(0, kLength);
  }

  /// Uppercase, strip everything that is not alphanumeric.
  ///
  /// Field-entered identifiers arrive as `TN-1234 5678`, `tn12345678`, and
  /// `TN 1234-5678` for the same patient. Without this, one patient becomes
  /// three rows in the PHC's queue and the de-duplication the shared salt was
  /// chosen to enable never actually happens.
  static String normalise(String raw) =>
      raw.toUpperCase().replaceAll(RegExp(r'[^A-Z0-9]'), '');
}
