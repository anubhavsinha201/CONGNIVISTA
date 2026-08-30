# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

ArogyaX — an offline atrial-fibrillation **screening and triage** layer for Tamil Nadu's
*Makkalai Thedi Maruthuvam* doorstep health scheme. A health worker's Android phone plus a
~₹2,500 single-lead ECG unit (ESP32 + AD8232 + MAX30102; no IMU — motion is inferred).

- **Product / concept doc:** [docs/PRODUCT.md](docs/PRODUCT.md)
- **Interface contracts:** [contracts/](contracts/) — locked; read before writing code

---

## Non-negotiables

Product and safety constraints, not preferences. Code that violates one is wrong even if
it works.

1. **Never display a diagnosis.** Output is referral urgency only. The strings
   "atrial fibrillation", "AF", "arrhythmia" must not reach the worker-facing UI.
   See [contracts/tiers.md](contracts/tiers.md) §1.
2. **Never score a bad signal.** SQI gate, motion gate, lead-off, and BLE sequence gaps
   all produce `RETAKE`, never a tier. Refusing to answer is a feature.
3. **A dropped BLE frame invalidates the window.** Concatenating across a gap fabricates a
   short RR interval, which looks exactly like AF. See [contracts/ble.md](contracts/ble.md) §3.
4. **Offline is the product, not a mode.** Every screening completes with the radio off.
   Sync is opportunistic and never blocks a result.
5. **No PII leaves the device.** Only a salted `patientPseudoId`. No name, phone, or Aadhaar.
6. **Contact PPG escalates, never reassures.** It can raise YELLOW or ORANGE to RED and can force a
   RETAKE, but must never downgrade a tier or clear a patient the ECG did not clear.
   See [contracts/ppg.md](contracts/ppg.md) §7.
7. **No model-generated text, ever.** All worker-facing strings come from a static,
   reviewable table.
8. **Every reported metric is measured on a held-out split, described accurately.** For
   CinC 2017 that is **record-disjoint, not patient-disjoint** — PhysioNet publishes no
   subject IDs. Anything unmeasured is labelled a target, not a result. See
   docs/PRODUCT.md §10 and [contracts/model.md](contracts/model.md) §5.

---

## The central architectural fact

**Every signal module has a line-for-line Python twin, and the Python is the verified
side.** This was true of the Dart implementation and stays true of its replacement.

**2026-08-30: the app target moved from Flutter/Dart to native Android/Kotlin** (ticket
019, user decision). `app/lib/*.dart` (~3,900 lines, Python-verified, never
Dart-compiled) is no longer the deliverable — it remains in the repo as the reference
the Kotlin port is built against, module by module. The diagram below describes the
pinning relationship itself, which does not change with the target language:

```
[Dart, superseded] or [Kotlin, current]   <--pinned to-->   ml/reference/*.py
        |                                                          |
   the deliverable                                       scipy-checked, run against
   (no compiler needed                                   synthetic signals with exact
    to verify the spec)                                  known ground truth
                                                                    |
                                                          app/test/fixtures/golden_vectors.json
```

The Python validates the algorithms independently (filter coefficients against
`scipy.signal.butter`; R-peak detection against synthetic ECG with exact known peak
positions), then emits golden vectors that the Dart tests assert against to 1e-6. The two
implementations are pinned to each other, and the pair is pinned to scipy and to ground
truth.

**If you change a DSP constant, change it in both, then regenerate the vectors.** The
tests fail loudly when they drift — that is the point. Five suites must stay green:

| Suite | Covers |
|---|---|
| `ml/reference/validate_dsp.py` | filters, Pan–Tompkins, RR features, SQI |
| `ml/reference/validate_policy.py` | the tier decision table and gate ordering |
| `ml/reference/validate_ppg.py` | PPG peaks, perfusion index, ECG/PPG fusion |
| `ml/reference/validate_record.py` | record schema v4, pseudo-ID, sync state machine, backoff |
| `ml/reference/validate_history.py` | repeat-visit history, clinician outcomes, which records train the model |

---

## Commands

```bash
# --- Flutter app [SUPERSEDED 2026-08-30, ticket 019 - kept as historical reference,
#     app/lib/*.dart is no longer the deliverable, do not expect these to matter now] ---
cd app && flutter pub get
cd app && flutter analyze                       # no analysis_options.yaml yet; see Gotchas
cd app && flutter test                          # all tests
cd app && flutter test test/policy_test.dart    # one file
cd app && flutter test --plain-name "irregular, normal rate, first time -> YELLOW"   # one test
cd app && flutter run

# --- Kotlin/Android app [current target, ticket 019] ---
# Needs a JDK 17 on JAVA_HOME. Android Studio's bundled jbr is 25, which Gradle 8.9
# rejects with a bare "What went wrong: 25.0.2" — see Gotchas.
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
cd android && ./gradlew test                      # both variants, 71 tests each (1 @Ignore'd)
cd android && ./gradlew testDebugUnitTest         # debug only, faster
cd android && ./gradlew installDebug

# --- ML pipeline ---
# MUST source wsl_env.sh, not the venv directly. See Gotchas.
source ml/wsl_env.sh
python ml/prepare_cinc2017.py          # -> ml/data/cinc2017_250hz.npz
python ml/train_af_cnn.py --seed 0     # -> ml/artifacts/af_int8_seed0.tflite
python ml/calibrate_threshold.py       # -> calibration.png + kCnnThresholdInt8
python ml/evaluate.py                  # -> full metrics at the deployed operating point

# --- Verification (pure Python, no Dart SDK required) ---
python ml/reference/validate_dsp.py    # also regenerates the golden vectors
python ml/reference/validate_policy.py
python ml/reference/validate_ppg.py
python ml/reference/validate_record.py
python ml/reference/validate_history.py

# --- Sync service + PHC dashboard ---
cd server && npm install
cd server && npm test                  # 49 tests, no database required
cd server && DEMO=1 npm start          # in-memory, nothing to install
cd server && docker compose up -d && npm start        # against local MongoDB
cd server && MONGO_URI="mongodb+srv://..." npm start  # against Atlas
cd dashboard && python -m http.server 8080            # needs the server on :8787

# --- Snowflake analytics export [ticket 018, contracts/analytics.md] ---
# Additive: reads MongoDB, writes Snowflake, touches nothing else. SNOWFLAKE_ACCOUNT /
# SNOWFLAKE_USER / SNOWFLAKE_PASSWORD / SNOWFLAKE_WAREHOUSE / SNOWFLAKE_DATABASE /
# SNOWFLAKE_SCHEMA as env vars only — same rule as MONGO_URI, never committed.
cd server && npm run export:snowflake                              # full export
cd server && npm run export:snowflake -- --since 2026-08-29T00:00:00Z  # incremental

# --- Firmware (drafted, never compiled — no PlatformIO in this environment) ---
cd firmware && pio run -t upload && pio device monitor -b 115200
```

---

## Layout and build status

Only mark something built after checking — several directories are still empty.

| Path | Status |
|---|---|
| `contracts/` | **Built.** BLE wire format, record schema, tier policy, model I/O, PPG. Single source of truth. |
| `app/lib/signal/`, `app/lib/core/policy.dart` | **Superseded, kept as the port reference.** Python-verified, never Dart-compiled, and now never will be — ticket 019 ports this to Kotlin. |
| `ml/` | **Built.** Preprocessing, training, INT8 calibration, evaluation, Python reference. |
| `app/assets/models/af_int8.tflite` | **Shipped.** seed 0, calibrated. Still the right model file for the Kotlin port to load. |
| `app/lib/data/` | **Superseded, kept as the port reference.** Offline queue (SQLCipher), pseudo-ID, sync engine, scheduler — same status as `app/lib/signal/` above. |
| `android/` | **Under active port.** Gradle 8.9 + wrapper committed; `./gradlew test` runs 71 tests (70 passing + 1 correctly `@Ignore`d live-server test) green from a bare shell. Signal chain, PPG/fusion, record/patient history, sync engine + client, and audio playback wiring all ported — ticket 019. |
| `server/` | **Built and tested.** 49 passing tests plus a live end-to-end run. MongoDB sync service — see [server/README.md](server/README.md). |
| `server/scripts/export_to_snowflake.js` | **Full pipeline proven live.** Ran for real against both systems: pulled an actual record from MongoDB Atlas and merged it into the live Snowflake trial account, verified by reading the same `record_id` back from both `screenings` and the `district_tier_trends` rollup. Ticket 018, [contracts/analytics.md](contracts/analytics.md). Additive: reads MongoDB, writes Snowflake, touches nothing else. |
| `dashboard/` | **Built.** Static referral queue + risk map, no CDN. |
| `firmware/` | **Compiled, flashed, and boot-verified on real hardware** — ticket 013. Build output in `firmware/.pio/` (gitignored). |
| `app/assets/replay/` | **Empty**, and superseded — the trace `ReplaySource` actually plays now lives at `android/app/src/main/assets/replay/`. |
| App UI, BLE, Tamil strings | **Not written.** (`SignalSource` is written — Kotlin, with a replay trace.) |

The DSP and decision layer deliberately depend on nothing beyond `dart:math` and
`dart:typed_data`, so they compile and their tests run with no device, no Bluetooth stack
and no Flutter engine. Keep it that way — it is what makes the decision path testable.

**`SignalSource` (planned) is the most important abstraction in the app.** It lets the app
run fully without hardware, and it is how the demo shows a real AF-positive case without
inducing AF in a teammate.

---

## Key technical decisions

| Decision | Choice | Why |
|---|---|---|
| Sample rate | 250 Hz, **timer-driven** | `delay()`-based sampling jitters, and jitter corrupts RR intervals — which *are* the AF signal |
| Sample units | Arbitrary ADU, not mV | AF is a timing problem; nothing is calibrated against a reference, so no mV claim is made |
| Detection | RR rules **OR** INT8 CNN | Screening biases to sensitivity, so OR'd rather than averaged. See the caveat under Current state |
| CNN input | Raw waveform, 30 s @ 250 Hz | Sees morphology the RR rules are structurally blind to, so the OR is additive rather than two correlated copies |
| Training data | CinC 2017, conditioned + resampled to 250 Hz | Train what you deploy: same filter chain and sample rate as the app, or the model learns one signal and is shown another |
| Negative class | Normal **and Other rhythm** | Ectopy raises RMSSD/pNN50/entropy just as AF does, so separating AF from other irregular rhythms is the CNN's one clinical contribution |
| Contact PPG | MAX30102, corroborates only | As a rhythm detector it is redundant with the ECG. Its value is **pulse deficit** — beats too weakly filled to reach the finger — which requires both sensors on one clock |
| Byte order | Little-endian everywhere | ESP32 native. Dart `ByteData` defaults to **big**-endian |
| Waveform rendering | `CustomPainter` on a ring buffer | Charting packages cannot hold 250 Hz |

---

## Current state — measured, not assumed

CinC 2017 record-disjoint test split, 1364 windows, 124 AF (9.1%). From `ml/evaluate.py`,
2026-08-29 (re-run after the fixes below; see `ml/artifacts/evaluation.json`):

| Detector | Se | Sp | PPV | F1 |
|---|---|---|---|---|
| RR rules only (refit, gate 0.50) | 0.750 | 0.848 | 0.330 | 0.458 |
| INT8 CNN only (refit threshold) | 0.911 | 0.804 | 0.317 | 0.471 |
| Rules OR CNN (what ships) | 0.952 | 0.706 | 0.244 | 0.389 |

At field prevalence (5.1%), per 100 screened: **4.9 true AF flagged, 27.9 false alarms,
0.2 AF missed** — roughly 1 in 7 referrals is real. Substantially better than this
project's own history: the rules gate previously fired on ~50% of healthy people
(Sp 0.497) and the combined OR ran at Sp 0.429 / ~54 false alarms per 100.

**Fixed: the rules gate.** The logistic centres in `rr_features.dart` were
literature-derived placeholders, never fitted to data. Refit against MIT-BIH AFDB via
`ml/reference/tune_rr_thresholds.py` (5-fold CV: Sp 0.702→0.911) and confirmed on the
independent CinC 2017 split above (Sp 0.497→0.848) — cross-dataset agreement, not an
AFDB-only artefact.

**Caught: a live instance of the exact failure the INT8 calibration work exists to
name.** The seed-0 CNN was retrained on 2026-08-29 — same architecture, different
weights, since full-integer quantisation is not bit-reproducible run to run — which moved
its correctly-fit threshold from 0.007812 to **0.1875**, a 24x change for a near-identical
operating point. Before this was caught, `ml/evaluate.py` had been re-run against the new
model while `Policy.kCnnThresholdInt8` still held the old value, and reported Sp 0.460 for
the CNN alone: not a worse model, but a threshold fit for one model silently carried over
to another, reproduced by accident inside this project's own artifacts directory.
`app/assets/models/af_int8.tflite` re-synced to the current `ml/artifacts/af_int8_seed0.tflite`
— they had also drifted apart (same size, different bytes).

**Still true:** the INT8 output is a single int8, so the test set lands on ~90 distinct
scores in steps of 1/256 against 1364 for FP32 — roughly a dozen operating points exist
anywhere in Se ∈ [0.80, 0.98]. Quantisation does not merely shift the operating point, it
collapses which points are reachable. See `ml/artifacts/calibration.png`.

**Unfitted thresholds** (targets, not results): `kPulseDeficitBpm`,
`kPerfusedBeatFractionLow`, and the two inferred-motion gates
(`kMotionWanderRatioGate`, `kMotionPerfusionInstabilityGate`) — no paired ECG+PPG AF
dataset and no labelled disturbed-vs-still captures exist in this build.

---

## Gotchas that cost hours

- **`source ml/wsl_env.sh` before any ML work.** Ubuntu's `/usr/bin/ptxas` is 12.4 and
  shadows the venv's 12.9; the RTX 5070 is Blackwell (`sm_120`), so XLA's Triton autotuner
  fails with `Autotuner could not compile any configs`. Inference still works, so a
  forward-only GPU check passes and *training* then fails — verify with a gradient step.
- **Ubuntu 26.04 ships Python 3.14; TensorFlow publishes cp310–cp313 only.** The venv at
  `~/arogyax-ml/.venv` is Python 3.13 for this reason.
- **`flutter_lints` is in `pubspec.yaml` but there is no `analysis_options.yaml`,** so the
  lints are not actually applied. Add one before relying on `flutter analyze`.
- **PTT is measured R-peak to PPG *systolic peak*, not to the pulse foot** (150–450 ms).
  A foot-derived window silently reports perfusing beats as non-perfusing — a bug that
  reads as a clinical finding.
- **`pulseDeficitBpm` is derived from `perfusedBeatFraction`,** not measured independently.
  One finding, two expressions. Never present them as corroborating each other.
- **Windows/OneDrive: `claude.md` and `CLAUDE.md` are the same file.** The product doc
  lives at `docs/PRODUCT.md` for this reason.
- **A golden-vector fixture is not automatically a Gradle input.** The Kotlin tests read
  `app/test/fixtures/*.json` through a relative `File()` path that reaches outside the
  module, which Gradle cannot see. Before this was declared in `app/build.gradle.kts`,
  regenerating the vectors left `:app:test` **UP-TO-DATE** — a deliberately corrupted
  fixture still produced `BUILD SUCCESSFUL`, because the suite never ran. If you add a
  test that reads a fixture from a new location, add it to that `inputs.dir` too.
- **Dart `.floor()` is not Kotlin `.toInt()`.** `.floor()` rounds toward negative
  infinity; `.toInt()` truncates toward zero. Every current port site (5 of them) is
  correct only because its operand is provably non-negative — array lengths times a
  positive fraction. If you translate a `.floor()` whose input can go negative, use
  `floor(x).toInt()`, and add a golden-vector case that actually exercises the negative
  branch, because none of the existing fixtures would catch it.
- **Gradle 8.9 will not run on Android Studio's bundled JBR 25.** It fails with the
  near-useless `What went wrong: 25.0.2`. Point `JAVA_HOME` at a JDK 17 (Temurin
  `jdk-17.0.20.101-hotspot` is installed) — 17 is also what `build.gradle.kts` targets.
- **OneDrive locks files under `android/app/build/`,** so Gradle intermittently dies with
  `Unable to delete directory ...	est-results	estReleaseUnitTestinary`. It is not a
  test failure. `rm -rf` that directory and re-run, or exclude `build/` from OneDrive sync.
  `.gitignore` keeps it out of git; nothing keeps it out of OneDrive.

---

## Demo integrity

The stage demo replays a **real, labelled AF recording** through the identical on-device
pipeline, because atrial fibrillation cannot be induced in a healthy teammate. Say so out
loud. Never present replayed data as a live capture.
