# Wayfinder map — ArogyaX end-to-end system

`wayfinder:map`

## Destination

**The round trip, running on a real Android phone.** Capture → tier → Tamil (written,
then spoken) → offline queue → sync → PHC dashboard → clinician records an outcome →
outcome returns to the phone. Both legs: **replay first**, then live capture from the
sensor unit.

Reached when a stranger can be handed the phone and the unit and walk that loop.

## Notes

- **This map carries execution, not only decisions** — an explicit override of
  wayfinder's plan-don't-do default, because the destination is a working system. Task
  tickets that build are legitimate here.
- **Domain:** offline clinical screening. Consult `CLAUDE.md` non-negotiables before any
  ticket; they are safety constraints, not preferences.
- **Contracts are the source of truth** — `contracts/ble.md`, `ppg.md`, `model.md`,
  `tiers.md`, `sync.md`, `record.schema.json`. Change the contract before the code.
- **Every Dart change must keep the Python mirrors green.** Five validators:
  `validate_dsp` (30), `validate_policy` (31), `validate_ppg` (28), `validate_history`
  (29), `validate_record`. Server: 40 tests.
- **Git discipline for this map:** commit and push to `origin/main` after each ticket
  resolves and its validators are green — the human asked for a rollback point per phase.
- Skills: `/prototype` for UI tickets, `/grilling` + `/domain-modeling` when a ticket
  turns out to hide a decision.

## Ground truth at charting time (2026-08-29)

| | |
|---|---|
| Built and verified | Signal chain, SQI, PPG + fusion, policy, patient history, INT8-calibrated model (83 KB), encrypted queue, sync engine, sync service (33 tests), read-only dashboard |
| **Never compiled** | All ~3,900 lines of Dart. No Dart SDK has ever touched it |
| Absent | `main.dart`, any UI, BLE, `SignalSource`, Tamil strings, replay traces |
| **Firmware drafted, uncompiled** | `firmware/` has a full `main.cpp` against `ble.md`/`ppg.md` now, but no PlatformIO here to build it and no Python-mirror equivalent to verify it against — see ticket 013 |
| Toolchain | **No Flutter, Dart, adb, Android SDK, or PlatformIO installed.** Node only |
| Hardware | ESP32 + AD8232 + MAX30102. Breadboarded, **never produced a clean trace** |
| **MPU-6050 dropped** | The motion gate loses its sensor. Motion becomes **inferred** from ECG baseline wander and PPG perfusion instability — both measured on the patient, unlike the phone's accelerometer. 19 files reference it as of charting |

## Decisions so far

- [008 — Schema v4: age band and village](tickets/008-schema-v4-demographics.md) —
  `ageBand`/`villageCode` required, `sex`/BP/`glucose` optional; also fixed a stale
  v2→v3 schema-version drift and a duplicate-argument compile bug found along the way.
  `8f4f857`.
- [004 — Wire the clinician outcome loop](tickets/004-clinician-outcome-loop.md) —
  `POST /v1/acks` now accepts `clinicianOutcome` alongside `referralState`, without
  either ever clobbering the other; dashboard gained an independent outcome control.
  `20bd8c1`.
- [006 — Replace the motion gate with inferred motion](tickets/006-inferred-motion-gate.md)
  — motion now OR's ECG baseline wander with PPG perfusion instability; both thresholds
  PROVISIONAL pending real disturbed captures (ticket 003). Unblocks firmware's BLE
  frame layout. `6e3381c`.
- [005 — Refit the beat-timing thresholds](tickets/005-refit-beat-timing.md) — rules
  detector refit against AFDB (5-fold CV: Sp 0.702→0.911), confirmed on an independent
  dataset (CinC 2017: Sp 0.497→0.848). Surfaced that the deployed OR-combination is
  bottlenecked by the CNN's own Sp, not the rules detector — flagged for ticket 007.
  `18ac3fa`.
- [007 — Five-state triage](tickets/007-five-state-triage.md) — GREEN/YELLOW/ORANGE/RED
  + RETAKE now implemented, `PatientHistory.isIntermittent`/`isPersistent` wired into
  `Policy.decide`. ORANGE = irregularity flagged and the pattern has been seen across
  visits; also reachable on a visit that is itself clean, when `isIntermittent` — the
  paroxysmal case a single clean strip is expected to miss. `isPersistent` alone does
  not get that bypass (a clean visit after an all-flagged history is read as a real
  result). The CNN's own Sp 0.460 bottleneck flagged by ticket 005 was deliberately
  **not** touched here — a separate, already-documented problem, not folded into this
  ticket. `31b1db8`.
- [013 — ESP32 firmware](tickets/013-esp32-firmware.md) — **not closed, code only.**
  `firmware/src/main.cpp` written against `ble.md`/`ppg.md`'s locked frame formats:
  timer-driven 250 Hz ECG + 100 Hz PPG sampling via `esp_timer` task-dispatch callbacks
  (not a hardware ISR — chosen so the sample read itself stays jitter-free even while
  `loop()` is mid-`notify()`), double-buffered handoff to BLE. No PlatformIO here to
  compile it and no Python mirror to check it against, unlike every other ticket in this
  tracker — the ticket file lists five specific things to verify first. `6ebf74e`.

- [016 — Reconcile the CNN threshold with the retrained model](tickets/016-reconcile-cnn-threshold-drift.md)
  — the seed-0 model was retrained outside any ticket, silently invalidating the shipped
  threshold (0.007812 → correctly-fit 0.1875) and the bundled app asset. Caught while
  checking hardware-test readiness. Rules OR CNN: Sp 0.429 → 0.706 (measured, not assumed).

## Not yet specified

- Whether ORANGE's 24h / YELLOW's 48h referral windows survive clinician review — landed
  in ticket 007 as PROVISIONAL, reasoned but not fitted, same status as the rest of
  `contracts/tiers.md` §3's DRAFT strings. Ticket 011 is where that review happens.
- How sensitive the inferred motion gate should be — needs real disturbed captures from
  the hardware bring-up ticket before a threshold can be chosen honestly.
- Whether the dashboard needs a per-patient timeline view — depends what the clinician
  loop feels like in use.
- Demo staging and script — depends on what actually works.
- Enclosure and physical presentation of the unit.
- Target phone model and its device-specific quirks.

## Out of scope

- **Chatbot** (Advanced Spec §15.3) — conflicts with the no-generated-text rule and needs
  its own safety design.
- **Actian / Snowflake / n8n** — past the destination; the round trip ends at the dashboard.
- **Field-level identity encryption** — real work, not on the route. Until built, don't claim it.
- **Model retraining pipeline** — needs collected outcomes that don't exist yet.
- **Six-lead upgrade, SpO2.**
