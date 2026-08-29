# 019 — Port the app from Flutter/Dart to native Android (Kotlin)

`wayfinder:task` · Status: **modules 1-2 ported and verified against real Gradle build**
— modules 3-7 not started

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
waits), and modules 3-7 from the scope list above.

## Module 2 (PPG + fusion) — ported and verified, one real finding along the way

`Ppg.kt` and `Fusion.kt`, same line-for-line-translation discipline as module 1. No
`golden_vectors.json`-equivalent fixture existed for PPG (`validate_ppg.py`'s checks are
all range/property-based — `Se >= 0.95`, not exact values), so
`ml/reference/generate_ppg_golden_vectors.py` was written to produce one:
`app/test/fixtures/ppg_golden_vectors.json`, 3 cases (clean pulse, AF with a pulse
deficit, cold-finger/weak-signal), generated by reusing `ppg_reference.py`'s
`synth_ppg()` unchanged.

**Real finding, not a translation bug:** `ppg.dart`'s `_perfusionIndex` and
`_perfusionStability` use a simple index-floor percentile
(`sorted[(length * 0.05).floor()]`) — the *same* method `dsp_reference.py` deliberately
uses for RR-entropy trimming, which is *why* module 1 matched to 1e-6 with no
adjustment. But `ppg_reference.py`'s `perfusion_index()`/`perfusion_stability()` use
`np.percentile`, which linearly interpolates — a genuinely different algorithm from
Dart's, silently drifted apart, never caught because `validate_ppg.py` never asserts
exact values. Found a second instance of the same class of drift in `fuse()`: Dart's
"median" PTT is `sorted[len // 2]` (not a true median for an even count), while
`ppg_reference.fuse()` uses `np.median` (which averages the two middle elements).

Since the Kotlin port's job is to match `ppg.dart` (the deliverable), not
`ppg_reference.py`, `generate_ppg_golden_vectors.py` computes expected values with
Dart's actual methods (documented inline in that script), not by calling
`ppg_reference.py`'s functions directly. **Not fixed in `ppg_reference.py` itself** —
that's a separate, deliberate decision for whoever owns the Python reference: leave Dart
and Kotlin as the source of truth on this specific formula, or make `ppg_reference.py`
match and re-verify `validate_ppg.py` still passes. Flagging, not deciding.

`gradle testDebugUnitTest`: **7/7 tests passing** (4 from module 1, 3 from module 2 —
PPG analysis, fusion, and the zero-cases sanity check), 0 failures.

## Note on the rest of the tracker

Tickets 009/010/012/014 still describe the right *behavior* (SignalSource abstraction,
capture flow, model loading, BLE parsing) — they just need their file-path/language
specifics re-read as Kotlin equivalents when each is actually taken up, not rewritten
preemptively before the port strategy for each is decided.
