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

**Every Dart signal module has a line-for-line Python twin, and the Python is the
verified side.**

```
app/lib/signal/*.dart   <--pinned to-->   ml/reference/*.py
        |                                        |
   the deliverable                     scipy-checked, run against
   (no Dart SDK needed                 synthetic signals with exact
    to verify it)                      known ground truth
                                                 |
                                       app/test/fixtures/golden_vectors.json
```

The Python validates the algorithms independently (filter coefficients against
`scipy.signal.butter`; R-peak detection against synthetic ECG with exact known peak
positions), then emits golden vectors that the Dart tests assert against to 1e-6. The two
implementations are pinned to each other, and the pair is pinned to scipy and to ground
truth.

**If you change a DSP constant, change it in both, then regenerate the vectors.** The
tests fail loudly when they drift — that is the point. Three suites must stay green:

| Suite | Covers |
|---|---|
| `ml/reference/validate_dsp.py` | filters, Pan–Tompkins, RR features, SQI |
| `ml/reference/validate_policy.py` | the tier decision table and gate ordering |
| `ml/reference/validate_ppg.py` | PPG peaks, perfusion index, ECG/PPG fusion |
| `ml/reference/validate_record.py` | record schema v2, pseudo-ID, sync state machine, backoff |

---

## Commands

```bash
# --- Flutter app ---
cd app && flutter pub get
cd app && flutter analyze                       # no analysis_options.yaml yet; see Gotchas
cd app && flutter test                          # all tests
cd app && flutter test test/policy_test.dart    # one file
cd app && flutter test --plain-name "irregular, normal rate, first time -> YELLOW"   # one test
cd app && flutter run

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

# --- Sync service + PHC dashboard ---
cd server && npm install
cd server && npm test                  # 33 tests, no database required
cd server && DEMO=1 npm start          # in-memory, nothing to install
cd server && docker compose up -d && npm start        # against local MongoDB
cd server && MONGO_URI="mongodb+srv://..." npm start  # against Atlas
cd dashboard && python -m http.server 8080            # needs the server on :8787

# --- Firmware (drafted, never compiled — no PlatformIO in this environment) ---
cd firmware && pio run -t upload && pio device monitor -b 115200
```

---

## Layout and build status

Only mark something built after checking — several directories are still empty.

| Path | Status |
|---|---|
| `contracts/` | **Built.** BLE wire format, record schema, tier policy, model I/O, PPG. Single source of truth. |
| `app/lib/signal/`, `app/lib/core/policy.dart` | **Built and verified — but never compiled.** No Dart SDK has run against it; expect small fixes on first `flutter test`. |
| `ml/` | **Built.** Preprocessing, training, INT8 calibration, evaluation, Python reference. |
| `app/assets/models/af_int8.tflite` | **Shipped.** seed 0, calibrated. |
| `app/lib/data/` | **Built, never compiled.** Offline queue (SQLCipher), pseudo-ID, sync engine, scheduler. Logic verified through the Python mirror, not by a Dart run. |
| `server/` | **Built and tested.** 33 passing tests plus a live end-to-end run. MongoDB sync service — see [server/README.md](server/README.md). |
| `dashboard/` | **Built.** Static referral queue + risk map, no CDN. |
| `firmware/` | **Drafted, never compiled.** No PlatformIO in this environment — see ticket 013. |
| `app/assets/replay/` | **Empty** — no replay traces yet, so `ReplaySource` has nothing to play. |
| App UI, BLE, `SignalSource`, Tamil strings | **Not written.** |

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

CinC 2017 record-disjoint test split, 1364 windows, 124 AF (9.1%). From `ml/evaluate.py`:

| Detector | Se | Sp | PPV | F1 |
|---|---|---|---|---|
| RR rules only (gate 0.50) | 0.960 | **0.497** | 0.160 | 0.275 |
| INT8 CNN only | 0.919 | 0.810 | 0.326 | 0.481 |
| Rules OR CNN (what ships) | 1.000 | **0.429** | 0.149 | 0.259 |

**Known defect: the rules gate at 0.50 is miscalibrated.** Non-AF windows average a rule
score of 0.536, so half of healthy people fire it. The logistic centres in
`rr_features.dart` are literature-derived placeholders marked `PROVISIONAL` — a threshold
never fitted on the data it is applied to, which is the same error the INT8 calibration
work exists to fix. At 5.1% field prevalence the OR configuration yields ~54 false alarms
per 100 screened. Raising the gate to ~0.95 gives Se 0.952 / Sp 0.773; the real fix is
refitting the constants against MIT-BIH AFDB (downloaded, `ml/data/afdb.zip`).

**INT8 calibration** (the differentiator, `Policy.kCnnThresholdInt8 = 0.007812`): carrying
the FP32 threshold over costs +0.008 mean sensitivity (sd 0.005, range 0.000–0.016 across
5 seeds). More striking, the INT8 output is a single int8, so the test set lands on 57–88
distinct scores in steps of 1/256 against 1364 for FP32 — only 11–21 operating points
exist anywhere in Se ∈ [0.80, 0.98]. Quantisation does not merely shift the operating
point, it collapses which points are reachable.

**Unfitted thresholds** (targets, not results): `kPulseDeficitBpm`,
`kPerfusedBeatFractionLow` — no paired ECG+PPG AF dataset exists in this build.

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

---

## Demo integrity

The stage demo replays a **real, labelled AF recording** through the identical on-device
pipeline, because atrial fibrillation cannot be induced in a healthy teammate. Say so out
loud. Never present replayed data as a live capture.
