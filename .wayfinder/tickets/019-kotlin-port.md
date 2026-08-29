# 019 — Port the app from Flutter/Dart to native Android (Kotlin)

`wayfinder:task` · Status: **module 1 (signal chain) ported and verified against real
Gradle build** — modules 2-7 not started

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

## Progress (2026-08-30)

**Toolchain, hand-assembled (no interactive Android Studio wizard available):**
`android/` project created by hand — `settings.gradle.kts`, root and `app/build.gradle.kts`,
`AndroidManifest.xml` (no activity yet; ticket 010 hasn't started). AGP 8.6.0 / Gradle 8.9 /
Kotlin 2.0.21, chosen deliberately over the current stable AGP 9.0 / Gradle 9.5 — AGP 9's
built-in-Kotlin-support change is too recent to be confident of its exact syntax without a
real reference project to check against, and correctness mattered more here than being on
the latest release. Android Studio's bundled JBR is JDK 25, which Gradle 8.9 doesn't
support (`gradle wrapper` failed outright); installed Temurin 17 separately and pointed
`JAVA_HOME` at it, which resolved it.

**Known gap: no `gradlew` committed yet.** The `wrapper` task's distribution-URL
validation step (`Test of distribution url https://services.gradle.org/... failed`) fails
consistently in this environment even though normal dependency downloads (AGP, Kotlin
plugin, junit, org.json — all resolved fine) clearly work — looks like that specific
validation request is blocked while general Maven/Google traffic isn't. Not chased further;
builds run fine against a locally-extracted Gradle 8.9 in the meantime. Whoever picks this
up next: either retry `gradle wrapper --gradle-version 8.9` from a network that allows it,
or install Gradle 8.9 directly and build with that.

**Module 1 (signal chain) ported and verified — real, not assumed:**
`android/app/src/main/kotlin/com/arogyax/signal/`: `Filters.kt` (Biquad + FilterChain,
RBJ cookbook coefficients, filtfilt with odd-reflection padding), `PanTompkins.kt`
(R-peak detection, adaptive dual-threshold, searchback, T-wave rejection),
`RrFeatures.kt` (RMSSD/pNN50/Shannon entropy, the AFDB-fitted logistic constants from
`rr_features.dart`), `Sqi.kt` (saturation/flatline/powerline/wander, Goertzel band power).
Line-for-line translations of the Dart source, not reimplementations from the algorithm
description — deliberately, to minimize the chance of introducing a new bug while
changing language.

`GoldenVectorsTest.kt` loads the exact same `app/test/fixtures/golden_vectors.json` the
Dart tests were meant to use and asserts at the same 1e-6 tolerance CLAUDE.md specifies.
Ran for real via `gradle testDebugUnitTest`: **4/4 tests passed, 0 failures** — SQI fields,
exact R-peak indices, and all RR-feature fields match across all 4 synthetic cases
(nsr_clean, af_clean, nsr_mains, af_noisy). One real bug caught and fixed along the way:
Kotlin nests block comments (Java/Dart don't), so a KDoc comment containing the literal
text `signal/*.dart` was parsed as opening a nested comment and broke the build with
"Unclosed comment" — reworded, not suppressed.

**Not ported yet:** `analysis.dart`'s `EcgAnalyser` orchestrator (module 1 is the four
pieces it calls, not the orchestrator itself — needs `Policy` from module 3 first, so it
waits), and modules 2-7 from the scope list above.

## Note on the rest of the tracker

Tickets 009/010/012/014 still describe the right *behavior* (SignalSource abstraction,
capture flow, model loading, BLE parsing) — they just need their file-path/language
specifics re-read as Kotlin equivalents when each is actually taken up, not rewritten
preemptively before the port strategy for each is decided.
