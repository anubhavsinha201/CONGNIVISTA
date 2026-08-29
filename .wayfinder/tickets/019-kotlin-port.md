# 019 — Port the app from Flutter/Dart to native Android (Kotlin)

`wayfinder:task` · Status: **modules 1, 2, 5, 8 fully ported and verified. Module 7's real
orchestration + real network client now built and proven against a live server. Module 3
(Policy) still paused by user request — its TYPES ported (not `decide()`) to unblock
module 4. Module 6 (encrypted store) still not started. 71 tests, 70 passing + 1 correctly
`@Ignore`d live-server test, across 13 classes.**

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

**Correction to an earlier note in this ticket: the wrapper was missing, and was added
by hand — the `wrapper` task did not produce it.** `android/gradle/wrapper/` was an empty
directory (git does not carry empty directories, so a fresh clone had nothing), there was
no `gradlew`/`gradlew.bat`, and no `gradle` on PATH or distribution in `~/.gradle` — the
only way anyone had run these tests was Android Studio driving its own Gradle. The four
files now committed came from Gradle's own repository at tag `v8.9.0`
(`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`, jar verified to contain
`org/gradle/wrapper/GradleWrapperMain.class`), plus a hand-written
`gradle-wrapper.properties`.

That properties file **pins `distributionSha256Sum`** to the published checksum for
`gradle-8.9-bin.zip` (`d725d707…cecab`), which the local distribution was verified
against before first use. Do not regenerate it with a plain `gradle wrapper` — that drops
the pin.

Java's `HttpURLConnection` times out following the `services.gradle.org` → GitHub releases
redirect in this network even though `curl` follows it fine, so the distribution was
fetched with `curl` into `~/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/`
and checksum-verified there. `./gradlew test` then runs clean from a bare shell with
`JAVA_HOME` on Temurin 17 — 9 tests, both debug and release variants.

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

## Modules 4, 5, 7 (2026-08-30) — with two deliberate scope narrowings, named up front

Continuing to skip module 3's actual `decide()` logic, but two of the remaining modules
turned out to need pieces of Policy's *shape* even so. Handled the same way module 8 was
pulled forward: port only what's needed, name the boundary explicitly, don't quietly do
more or less than asked.

**Module 5 — Record + patient history, fully ported, no Policy dependency at all.**
Checking `record.dart` and `patient_history.dart` directly (not assuming) showed neither
needs `Policy.decide()` — `ScreeningRecord.tier`/`decidedBy` are plain strings matching
the wire format, and `PatientHistory` only ever compares tier as a string. The one thing
skipped is `ScreeningRecord.fromAnalysis()`, which *does* take a `ScreeningAnalysis`
(module 3's output) — not ported, since there's nothing to build it from yet.

- `PseudoId.kt` — HMAC-SHA256 pseudo-ID derivation (`javax.crypto.Mac`, no dependency
  needed). `ScreeningRecord.kt` — the full v4 schema, `toJson`/`fromJson`/`toSyncJson`,
  `validate()`, `newRecordId()`. `PatientHistory.kt` — the full longitudinal-risk feature
  set (flag rate, intermittency, burden confidence, adaptive repeat interval).
- Uses `org.json:json` as a real `implementation` dependency (not `testImplementation`)
  — Android's own `org.json.JSONObject` is a compile-only stub that throws "not mocked"
  in a plain JVM test; the real artifact is what makes the same code run in both places
  (device uses the platform's own classes at runtime regardless).
- Tests mirror `ml/reference/validate_history.py`'s checks one-to-one, same scenario
  names, so three implementations (Dart source, Python mirror, this port) can't quietly
  drift apart from each other. One real bug caught in the *test*, not the port: hardcoding
  a fixed reference date for `daysUntilDue` assertions ignored that `PatientHistory` calls
  the real, non-injectable `OffsetDateTime.now()` (faithfully matching Dart's
  `DateTime.now()`) — any nonzero time between building a fixture and the class's own
  `now()` call pushes a duration just under a day boundary, and truncating day-math
  always rounds that down. Fixed by asserting the 2-value range truncation can actually
  produce, not a single literal — a `-1` day-count discrepancy is expected behavior here,
  not flakiness to paper over.

**Module 4 — Explanation, needed a *slice* of Policy's shape, not `decide()`.**
`explanation.dart` reads `Policy`'s enums (`Tier`, `DecidedBy`, `RetakeReason`,
`PpgCorroboration`), two data classes (`TierDecision`, `TierInputs`), and exactly four
named constants (`kRrIrregularityGate`, `kCnnThresholdInt8`, `kHrLow`, `kHrHigh`).
`PolicyTypes.kt` ports precisely that and nothing else — its header comment names what's
deliberately absent (`decide()`, `kSqiGate`, `kMinRrIntervals`, both motion-gate
constants) so the boundary stays visible at a glance rather than eroding the next time
someone touches the file. `Explanation.kt` ports `Reason`/`Explainer`/`EXPLANATION_KEYS`
in full. 14 tests, including one that walks every `RetakeReason`/`DecidedBy`/
`PpgCorroboration` branch and asserts every key it observes is covered by
`EXPLANATION_KEYS` — a missing key here is a blank line on a worker's screen at a
doorstep, per the Dart doc comment this test takes at its word.

**Module 7 — narrowed to the pure state machine, not `SyncEngine`/`LocalStore`
themselves.** `sync.dart`'s `SyncEngine` orchestrates a real `LocalStore` (encrypted,
module 6 — SQLCipher for Android vs Jetpack Security, a real comparison ticket 019 always
said this needed, not made here) and a real `SyncClient` (HTTP, not built). Porting
`SyncEngine` faithfully would mean either building both of those first or stubbing them
dishonestly. What's fully specified and fully testable *without* either: the backoff
ladder (`nextRetryAt`'s jittered exponential climb, 5s→30s→2m→10m→30m→1h) and the
per-record state machine (`pending`/`synced`/`failed`, `nextBatch`, `applyAck` scoped to
synced rows only) — exactly what `validate_record.py`'s `FakeQueue` already mirrors on
the Python side, because it's the same kind of storage-independent contract
`PolicyTypes.kt` is for Policy. `Backoff.kt` + `SyncQueue.kt`, 9 tests matching
`validate_record.py` sections 6-7 by scenario name. This becomes the state machine the
real `LocalStore` sits on top of once module 6's storage choice is actually made — not a
placeholder to throw away.

**Not touched, and shouldn't be read as forgotten:** module 6 (encrypted store) is a
materially different kind of task — it needs a real choice between SQLCipher-for-Android
and Jetpack Security, and genuine verification needs Android instrumentation (a real
`Context`, real file-system encryption-at-rest checks), not a plain JVM unit test the way
everything above was checked. `SyncEngine`'s actual orchestration logic (the retry loop,
`flush()`/`flushNow()`) and `SyncClient` (real HTTP) are similarly still open.

`gradle testDebugUnitTest`: **56/56 tests passing**, 8 test classes, 0 failures.

## Note on the rest of the tracker

Tickets 009/010/012/014 still describe the right *behavior* (SignalSource abstraction,
capture flow, model loading, BLE parsing) — they just need their file-path/language
specifics re-read as Kotlin equivalents when each is actually taken up, not rewritten
preemptively before the port strategy for each is decided.

## Module 7 completed for real: SyncClient + SyncEngine orchestration (2026-08-30)

User asked to finish what module 7 had deliberately left narrow, plus the audio
playback wiring `ExplanationAudio`/`TierAudioClips` had deliberately left undone.

**`SyncClient.kt` — real HTTP, against the live server, not the contract doc alone.**
`HttpSyncClient` implements `contracts/sync.md`'s wire protocol with `HttpURLConnection`,
not a newer client — deliberately: `java.net.http.HttpClient` (Java 11+) isn't available
on Android below API 34, and this product's target is a budget Android phone
(`docs/PRODUCT.md`), not a new-enough one to assume that. `HttpURLConnection` has existed
since API 1. Zero new dependency either way; this is the more-compatible choice for the
actual deployment target, not just the smaller one.

**`SyncEngine.kt` — the real orchestration**, sitting on top of `SyncQueue` (module 7's
existing pure state machine, extended to hold the actual `ScreeningRecord` payload, not
just an ID, now that there's something real to upload) and the new `SyncClient`. Full
`flush()`/`flushNow()`/backoff/ack-pulling logic ported from `sync.dart`'s `SyncEngine`.

**Verified against the live Atlas-backed server, not mocked:** restarted `server/` fresh
against the real cluster (killed an ambiguous already-running instance first, to remove
any doubt about which backend it was using), then ran a real `SyncEngine.flush()` from
Kotlin against it. Result, independently re-checked via a direct `GET /v1/queue` call
before the code even reported success: the exact record inserted into `SyncQueue`
appeared server-side, `report.accepted == 1`, `QueuedRecord.syncState == SYNCED`. Test
record deleted from Atlas afterward, same hygiene as tickets 017/018.

This one test (`SyncEngineIntegrationTest`) is `@Ignore`d in the committed source — every
other suite in this project runs hermetically (no device, no network), and this is the
first one that genuinely needs a live server, so by default it would break that property
for anyone else running `gradle test`. Run it deliberately (remove the annotation, or an
IDE "run single test") when a live server is available to check against — the same
on-demand relationship `ml/reference/export_replay_trace.py`-style scripts already have
with the rest of the test suite.

## Audio playback wiring completed, with an honest verification limit named (2026-08-30)

`ExplanationAudio`/`TierAudioClips` (ticket 015) compute *what* to play; nothing called
an actual player. Split the same way everything Android-framework-touching has been
split all along:

- **`SequencePlayer` + `ClipPlayer`** (`TamilAudioPlayer.kt`) — the real logic (play
  clips in order, skip a clip that errors rather than aborting the sentence, cancel
  mid-sequence) behind a small interface, so it's testable with a fake player and no
  `android.media.MediaPlayer` at all. 5 new tests, 0 device dependency.
- **`MediaPlayerClipPlayer`** — the actual `android.media.MediaPlayer` adapter.
  **Deliberately not unit-tested, and not fakeable into being tested**: Android's real
  `MediaPlayer` is a compile-only stub outside a device/emulator, and a Robolectric shadow
  would only be simulating the exact behavior this class exists to get right — proving
  the shadow works, not that the real thing does. Correct by inspection against the
  documented `MediaPlayer` API; genuine verification needs ticket 010's UI and a real
  device or emulator, the same honest limit ticket 013's firmware had before it got
  flashed.

`gradle testDebugUnitTest`: **71 tests, 70 passing, 1 correctly `@Ignore`d, across 13
classes.**
