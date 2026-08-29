# 019 — Port the app from Flutter/Dart to native Android (Kotlin)

`wayfinder:task` · Status: **modules 1-2 ported and verified; module 3 (Policy) paused
by user request — module 8 (SignalSource/ReplaySource) pulled forward instead, see
ticket 009**

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

**Correction: `gradlew` exists after all.** The `wrapper` task reported FAILURE three
times in a row (`Test of distribution url https://services.gradle.org/... failed`), which
looked like the wrapper never got generated — but that validation is the task's *last*
step, after it already writes `gradle/wrapper/gradle-wrapper.jar` and
`gradle-wrapper.properties`. Found the files present anyway, tested
`./gradlew.bat testDebugUnitTest` directly, and it works cleanly (Gradle 8.9, all 9 tests
pass). Committed. The URL-validation step itself still fails in this network for reasons
not chased down (general Maven/Google traffic clearly works fine); harmless since the
wrapper is otherwise complete and correct.

**Recurring, unrelated nuisance worth naming:** `android/app/build/test-results/` hit a
Windows/OneDrive file-lock or cloud-placeholder issue **three separate times** during this
session (`Unable to delete directory`, then `not a regular file` on a retry) — this repo
lives inside a OneDrive-synced folder, same root cause as the firmware `.pio` issue
earlier. Fix each time was `rm -rf` the stuck directory and rerun; never a real code
problem. If this becomes a recurring drag, excluding `android/app/build/` from OneDrive
sync (right-click → "Always keep on this device" is the wrong direction; the actual fix
is a `.txt`-style exclusion in OneDrive's own settings, or moving the checkout outside
OneDrive entirely) would remove it permanently.

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

**Resolved, 2026-08-30 (outside this session):** `ppg_reference.py` was corrected to
Dart's actual semantics — a `_percentile_floor` helper now backs `perfusion_index`,
`perfusion_stability`, `detect_systolic_peaks`'s threshold, and `fuse`'s median PTT
uniformly (the fix also caught the same index-floor-vs-percentile pattern in
`detect_systolic_peaks`, which this ticket hadn't flagged). `generate_ppg_golden_vectors.py`
now calls `ppg_reference.py` directly again — the Dart-faithful workaround copies this
ticket originally needed are gone. Confirmed correct the rigorous way, not just
plausible: regenerating the fixture from the corrected reference reproduced the exact
same bytes this session's workaround-based version had already produced. Both
`validate_ppg.py` and the Kotlin `PpgGoldenVectorsTest` stay green against one, now
genuinely shared, reference.

`gradle testDebugUnitTest`: **7/7 tests passing** (4 from module 1, 3 from module 2 —
PPG analysis, fusion, and the zero-cases sanity check), 0 failures.

## Module 3 paused (user request, 2026-08-30); module 8 pulled forward instead

User asked to skip Policy for now and move to the next phase. `SignalSource`/
`ReplaySource` (originally module 8, listed last only because nothing else had been
built yet to need it) doesn't depend on Policy at all — it just supplies raw samples to
the already-ported signal chain — so it was taken up out of order instead of leaving
this ticket idle. Full detail in ticket 009, which now owns this piece. `gradle
testDebugUnitTest`: 9/9 across all three test classes.

Module 3 (Policy) is still next once resumed — it's the safety-critical one
(non-negotiables 1, 2, 6), checked against `validate_policy.py`'s 35 cases.

## Note on the rest of the tracker

Tickets 009/010/012/014 still describe the right *behavior* (SignalSource abstraction,
capture flow, model loading, BLE parsing) — they just need their file-path/language
specifics re-read as Kotlin equivalents when each is actually taken up, not rewritten
preemptively before the port strategy for each is decided.
