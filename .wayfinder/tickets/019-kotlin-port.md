# 019 — Port the app from Flutter/Dart to native Android (Kotlin)

`wayfinder:task` · Status: **open — toolchain installing**

## Question

User decision (2026-08-30), reversing what CLAUDE.md calls "the central architectural
fact" of this repo: Flutter/Dart is dropped as the app implementation target in favor of
a native Android app in Kotlin. **This supersedes ticket 002** ("First compile of the
Dart") — there is no more Dart to compile toward.

**What does NOT change:** the Python reference mirrors (`ml/reference/*.py`) stay the
pinned, verified ground truth — filter coefficients checked against `scipy.signal.butter`,
R-peak detection against synthetic ECG with known peak positions, the tier decision table,
the patient-history math. Porting to Kotlin means re-implementing already-designed,
already-verified algorithms in a new language, checked against the same
`app/test/fixtures/golden_vectors.json` and the same five Python validators — not
redesigning them from scratch. That materially de-risks this compared to a greenfield
port: the hard decisions (thresholds, gate ordering, exact formulas, the five-state tier
logic from ticket 007) are already made and documented.

**What DOES get thrown away:** ~3,900 lines of written, Python-verified Dart
(`app/lib/signal/*`, `app/lib/core/policy.dart` + `explanation.dart`,
`app/lib/data/record.dart` + `patient_history.dart` + the SQLCipher local store + sync
engine). None of it compiles to anything in a native Android/Kotlin app. It remains in
the repo as a reference for the port, not as code that ships.

## Scope — modules to port, roughly in dependency order

1. **Signal chain** (`app/lib/signal/`): filters (Butterworth bandpass/notch),
   Pan-Tompkins R-peak detection, RR features (RMSSD/pNN50/Shannon entropy,
   irregularity score), SQI. Verify against `golden_vectors.json` — same fixtures, new
   language, exact-match assertions (1e-6), same as the Dart side was meant to.
2. **PPG + fusion** (`app/lib/signal/ppg.dart`, `fusion.dart`): peak detection,
   perfusion index, perfusion stability, pulse deficit / perfused-beat-fraction.
3. **Policy** (`app/lib/core/policy.dart`): the five-state tier decision engine —
   this is the safety-critical part (CLAUDE.md non-negotiables 1, 2, 6). Port carefully;
   `ml/reference/validate_policy.py` (35 checks) is the spec to match exactly, including
   the history-driven ORANGE bypass from ticket 007.
4. **Explanation** (`app/lib/core/explanation.dart`): the templated reason-string
   system — non-negotiable 7, no generated text, ever.
5. **Record + patient history** (`app/lib/data/record.dart`, `patient_history.dart`):
   schema v4, pseudo-ID, the longitudinal risk/burden/repeat-interval math.
6. **Encrypted local store**: SQLCipher-equivalent on Android (likely SQLCipher for
   Android directly, or Android's `EncryptedFile`/Jetpack Security as an alternative —
   worth a real comparison, not a default pick, since this is the one non-negotiable-5
   ("no PII leaves the device") mechanism).
7. **Sync engine**: the backoff ladder, idempotency, offline queue state machine —
   `ml/reference/validate_record.py` already covers this spec.
8. **Never built in any language yet, so nothing is "lost" starting fresh in Kotlin**:
   `SignalSource`/`BleSource` (tickets 009, 014), model loading (ticket 012), the actual
   UI (ticket 010), Tamil strings (ticket 011).

## Toolchain

Android Studio (bundles JDK, Android SDK, Gradle, Kotlin plugin) — one install covers
what Flutter+Android-SDK used to need two for. Installing now via
`winget install Google.AndroidStudio`.

Blocked by: nothing to start the toolchain install. Actual porting work is next.
Blocks: everything client-side — 009, 010, 011 (unaffected content-wise, just a new
consumer), 012, 014 all now target Kotlin instead of Dart.

## Note on the rest of the tracker

Tickets 009/010/012/014 still describe the right *behavior* (SignalSource abstraction,
capture flow, model loading, BLE parsing) — they just need their file-path/language
specifics re-read as Kotlin equivalents when each is actually taken up, not rewritten
preemptively before the port strategy for each is decided.
