# ArogyaX — Features

What the system actually does, by component. For the clinical pitch see
[PRODUCT.md](PRODUCT.md); architecture and gotchas live in this project's internal CLAUDE.md notes.
Status tags reflect the repo as of 2026-08-30 — checked against code, not aspirational.

---

## Screening pipeline (Android app — `android/`)

- **Offline AF screening** — full capture-to-tier decision runs with the radio off; sync
  is opportunistic and never blocks a result.
- **Live BLE capture** — connects to the ESP32 sensor unit, streams the 30 s ECG
  window over BLE, and renders it on a live rolling trace as it arrives. A dropped
  BLE frame invalidates the whole window rather than being concatenated across it.
- **Signal chain** — bandpass/notch filtering, Pan–Tompkins R-peak detection, RR-interval
  features, signal-quality index (SQI). Line-for-line pinned to `ml/reference/validate_dsp.py`.
- **Dual detector, OR'd** — fitted RR-interval rules plus an INT8 CNN over the raw 30 s
  waveform; either can trigger a positive read (screening biases to sensitivity, so OR
  rather than average).
- **Gate-first policy** (`Policy.decide()`) — lead-off, BLE sequence-gap, inferred-motion,
  SQI, and minimum-RR-count gates all run *before* scoring; the first failing gate returns
  `RETAKE` and no model is ever consulted. Never scores a bad signal.
- **Motion inferred from ECG/PPG, not an IMU** — the sensor unit has no accelerometer;
  baseline wander and (when available) PPG perfusion instability stand in for it.
- **Contact-PPG fusion** — corroborates the ECG read via pulse deficit (beats too weak to
  perfuse the finger); can escalate a tier or force a RETAKE, never downgrade one.
- **Referral-priority tiers, not a diagnosis** — `RETAKE / GREEN / YELLOW / ORANGE / RED`
  only; the strings "atrial fibrillation", "AF", "arrhythmia" never reach this surface.
- **Repeat-visit history in the tier itself** — a patient seen before with a borderline
  read is weighted differently (YELLOW vs ORANGE) than a first-time capture, inside
  `Policy.decide()` — distinct from the longitudinal risk engine below, which layers
  priority and trajectory on top of whatever tier this produces.
- **Tamil explanations** — templated, clinician-reviewable worker-facing strings plus
  matching spoken audio; nothing is model-generated. *Status: 39 strings + 239 ElevenLabs
  clips drafted, not yet native-speaker or clinician reviewed.*
- **Encrypted offline queue** — screenings persist locally, AES-256-GCM with the key
  held in the Android Keystore (SQLCipher would need a network download this build
  deliberately avoids — see Gotchas), until a sync opportunity appears.
- **Salted pseudo-ID only** — no name, phone, or Aadhaar ever leaves the device.
- **BLE ingestion** — little-endian frame parsing for the ECG/PPG wire format; a dropped
  frame invalidates the whole capture window rather than being concatenated across.
- **Replay `SignalSource`** — plays a bundled, real, labelled PhysioNet AF trace through
  the identical on-device pipeline for demos and development without hardware or an
  induced arrhythmia in a teammate.
- **Worker-facing UI** — plain Android Views in Tamil Nadu government service styling
  (Compose deliberately reverted — see Gotchas in CLAUDE.md); waveform rendered on a
  min/max-decimating Canvas view, not a charting library. Eight screens: home, patient
  entry, capture, result, patient timeline, referral queue, district overview, assistant.
- **Longitudinal risk engine** — `RiskEngine` combines this visit's tier with repeat-visit
  history into a screening priority and trajectory (stable / increasing / repeatedly
  suspicious); can only ever raise the priority a visit's own tier already earned, never
  lower one — asserted exhaustively in tests, not just by example.
- **ECG quality panel** — names which of four factors (contact, amplitude, noise,
  steadiness) actually cost the signal quality score, instead of showing a bare percentage.
- **Adaptive repeat, with a hard stop** — targeted, reason-specific retry instructions
  (loose electrode vs. motion vs. noise get different guidance); refuses to ask for a
  fourth attempt and escalates to a clinician instead of retrying forever.
- **In-app assistant** — answers a closed set of questions from the patient's own record;
  refuses medical/treatment questions outright rather than attempting an answer.

## Sensor unit (firmware — `firmware/`)

- ESP32 + AD8232 (single-lead ECG) + MAX30102 (contact PPG), one shared timer-driven
  clock so both signals land on the same sample grid (needed for pulse-deficit fusion).
- 250 Hz ECG / 100 Hz PPG, streamed over BLE in fixed-size little-endian frames.
- **Status: compiled, flashed, and boot-verified on real hardware** (ticket 013).

## ML pipeline (`ml/`)

- CinC 2017 preprocessing → 250 Hz, matched to the app's own filter chain ("train what
  you deploy").
- 5-block strided 1D CNN over the raw 30 s waveform, trained per-seed.
- **INT8 quantisation with a re-fitted decision threshold** — the project's technical
  differentiator: the threshold is refit on the quantized model's own score distribution
  rather than carried over from FP32, which this repo caught silently degrading
  sensitivity once already (§ Current state, CLAUDE.md).
  Reasoning: RR-rules-only + INT8-CNN-only + OR'd combined, at the record-disjoint
  CinC 2017 test split.
- Golden-vector generation (`ml/reference/*.py`) — the Python reference is the verified
  side of every signal/policy module; five `validate_*.py` suites cross-check DSP, policy,
  PPG, record schema, and history logic against scipy and synthetic ground truth.

## Sync service + dashboard (`server/`, `dashboard/`)

- MongoDB-backed sync service accepting queued screenings from the app whenever
  connectivity appears; idempotent, no live network required for a result to exist.
- Auth, CORS hardening, backoff-aware client contract (`contracts/sync.md`).
- Static PHC referral queue + risk map dashboard (geo-tagged by the phone's GPS at
  capture time), no CDN dependency.
- **Snowflake analytics export** (`server/scripts/export_to_snowflake.js`) — additive,
  read-MongoDB/write-Snowflake district-level rollups; proven against live Atlas +
  Snowflake trial accounts (ticket 018).
- Seed/verify scripts for synthetic data and track integrity
  (`server/scripts/seed_synthetic.js`, `verify_tracks.js`).

## Interface contracts (`contracts/`)

Locked, single-source-of-truth specs that both the Kotlin app and the server code against:
BLE wire format, record schema v4, tier decision policy, model I/O, PPG fusion, sync
protocol, analytics export.

---

## Explicitly out of scope

No diagnosis, ever — only referral urgency. No 12-lead / infarct localisation. No
telemetry that requires a live network to produce a result. See CLAUDE.md "Non-negotiables"
for the full list and the reasoning behind each.
