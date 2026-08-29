import 'package:flutter_test/flutter_test.dart';

import 'package:arogyax/data/pseudo_id.dart';

/// The deployment-wide salt is a deliberate trade-off, and these tests pin both
/// halves of it: the property that makes the PHC's referral queue usable (same
/// patient, same value, every phone), and the property that keeps the raw MTM
/// identifier off the wire.
///
/// Mirrored in ml/reference/validate_record.py section 5.

const salt = 'tn-coimbatore-2026';
const otherSalt = 'tn-salem-2026';

void main() {
  group('re-identification at the PHC', () {
    test('the same patient on two phones produces the same value', () {
      // This is why the salt is shared rather than per-device. Without it the
      // PHC cannot match a record to its own MTM roll, and one patient
      // screened twice appears as two people in the referral queue.
      final a = PseudoId.derive('TN-1234 5678', deploymentSalt: salt);
      final b = PseudoId.derive('TN-1234 5678', deploymentSalt: salt);
      expect(a, b);
    });

    test('field-entry variations of one identifier collapse to one value', () {
      // 'TN-1234 5678', 'tn12345678' and 'TN 1234-5678' are the same patient
      // typed by three workers. Without normalisation the de-duplication the
      // shared salt was chosen to enable never actually happens.
      final canonical = PseudoId.derive('TN-1234 5678', deploymentSalt: salt);
      for (final variant in const [
        'tn12345678',
        'TN 1234-5678',
        '  TN-1234-5678  ',
        'tn/1234/5678',
      ]) {
        expect(PseudoId.derive(variant, deploymentSalt: salt), canonical,
            reason: 'variant "$variant" did not normalise');
      }
    });

    test('different patients do not collide', () {
      final seen = <String>{};
      for (var i = 0; i < 20000; i++) {
        seen.add(PseudoId.derive('TN-$i', deploymentSalt: salt));
      }
      expect(seen.length, 20000);
    });
  });

  group('separation between deployments', () {
    test('a different district gets a different value for one patient', () {
      expect(
        PseudoId.derive('TN-1234 5678', deploymentSalt: salt),
        isNot(PseudoId.derive('TN-1234 5678', deploymentSalt: otherSalt)),
      );
    });
  });

  group('what must not leak', () {
    test('the raw identifier does not appear in the output', () {
      const raw = 'TN-1234 5678';
      final id = PseudoId.derive(raw, deploymentSalt: salt);
      expect(id.contains('1234'), isFalse);
      expect(id.contains('5678'), isFalse);
      expect(PseudoId.normalise(id).contains(PseudoId.normalise(raw)), isFalse);
    });

    test('the salt does not appear in the output', () {
      final id = PseudoId.derive('TN-1234 5678', deploymentSalt: salt);
      expect(id.contains(salt), isFalse);
    });

    test('the output is hex only', () {
      final id = PseudoId.derive('TN-1234 5678', deploymentSalt: salt);
      expect(RegExp(r'^[0-9a-f]+$').hasMatch(id), isTrue);
    });
  });

  group('shape', () {
    test('satisfies the schema minLength of 8', () {
      expect(PseudoId.derive('TN-1', deploymentSalt: salt).length,
          greaterThanOrEqualTo(8));
      expect(PseudoId.derive('TN-1', deploymentSalt: salt).length,
          PseudoId.kLength);
    });

    test('length does not vary with the input', () {
      for (final raw in const ['A', 'TN-1234 5678', 'X' * 500]) {
        expect(PseudoId.derive(raw, deploymentSalt: salt).length,
            PseudoId.kLength);
      }
    });
  });

  group('refusals', () {
    test('an empty salt throws rather than producing an unsalted hash', () {
      // Silently hashing without a salt would make every record trivially
      // re-identifiable by anyone with a roll and a SHA-256 implementation.
      expect(
        () => PseudoId.derive('TN-1234 5678', deploymentSalt: ''),
        throwsArgumentError,
      );
    });

    test('an identifier that normalises to nothing throws', () {
      expect(() => PseudoId.derive('---', deploymentSalt: salt),
          throwsArgumentError);
      expect(() => PseudoId.derive('', deploymentSalt: salt),
          throwsArgumentError);
    });
  });

  group('normalise', () {
    test('uppercases and strips punctuation and whitespace', () {
      expect(PseudoId.normalise('tn-1234 5678'), 'TN12345678');
      expect(PseudoId.normalise('  a/b.c  '), 'ABC');
    });
  });
}
