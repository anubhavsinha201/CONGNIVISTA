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
  `validate_dsp` (30), `validate_policy` (24), `validate_ppg` (21), `validate_history`
  (26), `validate_record`. Server: 33 tests.
- **Git discipline for this map:** commit and push to `origin/main` after each ticket
  resolves and its validators are green — the human asked for a rollback point per phase.
- Skills: `/prototype` for UI tickets, `/grilling` + `/domain-modeling` when a ticket
  turns out to hide a decision.

## Ground truth at charting time (2026-08-29)

| | |
|---|---|
| Built and verified | Signal chain, SQI, PPG + fusion, policy, patient history, INT8-calibrated model (83 KB), encrypted queue, sync engine, sync service (33 tests), read-only dashboard |
| **Never compiled** | All ~3,900 lines of Dart. No Dart SDK has ever touched it |
| Absent | `main.dart`, any UI, BLE, `SignalSource`, Tamil strings, `firmware/`, replay traces |
| Toolchain | **No Flutter, Dart, adb, Android SDK, or PlatformIO installed.** Node only |
| Hardware | ESP32 + AD8232 + MAX30102. Breadboarded, **never produced a clean trace** |
| **MPU-6050 dropped** | The motion gate loses its sensor. Motion becomes **inferred** from ECG baseline wander and PPG perfusion instability — both measured on the patient, unlike the phone's accelerometer. 19 files reference it as of charting |

## Decisions so far

- [008 — Schema v4: age band and village](tickets/008-schema-v4-demographics.md) —
  `ageBand`/`villageCode` required, `sex`/BP/`glucose` optional; also fixed a stale
  v2→v3 schema-version drift and a duplicate-argument compile bug found along the way.
  `8f4f857`.

## Not yet specified

- How YELLOW and ORANGE thresholds get set from history — needs Five-state triage landed
  and the threshold refit measured.
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
