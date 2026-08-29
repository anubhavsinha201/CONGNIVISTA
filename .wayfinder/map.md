# Wayfinder map — ArogyaX end-to-end system

`wayfinder:map`

## Destination

**The round trip, running on a real Android phone.** Capture → tier → Tamil (written,
then spoken) → offline queue → sync → PHC dashboard → clinician records an outcome →
outcome returns to the phone. Both legs: **replay first**, then live capture from the
sensor unit.

Reached when a stranger can be handed the phone and the unit and walk that loop.

**Second leg, added 2026-08-30 — deliberately reopening a prior scope decision.**
MongoDB's referral/screening records (pseudo-IDs only, no PII by construction) feed a
Snowflake analytics layer for district-level aggregate reporting — the same thing
`docs/PRODUCT.md` §9's roadmap already names ("district-level risk analytics"), just
pulled forward. This is genuinely past the original destination, done for track-prize
eligibility, not because the round trip needed it. Reached when a district-level view
(counts by tier, by village, over time) is queryable in Snowflake from real synced data,
not sample rows.

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
| Built and verified | Signal chain, SQI, PPG + fusion, policy, patient history, INT8-calibrated model (83 KB), encrypted queue, sync engine, sync service (33 tests), read-only dashboard — **all as the Python-verified spec; the Dart implementation of these is now superseded, see below** |
| **MongoDB Atlas — real, not simulated** | Server ran against a live M0 cluster (`cluster0.jqe5dp9.mongodb.net`); a real record POSTed through `/v1/records:batch` and read back through `/v1/queue`. One synthetic test record left in the `arogyax` DB, harmless but worth clearing before a real demo. See ticket 017 |
| **Snowflake analytics leg — full pipeline proven** | `npm run export:snowflake` ran for real against both live systems: pulled the actual record from MongoDB Atlas (the same one ticket 017 POSTed) and merged it into the live Snowflake trial account (`ed06748.ap-southeast-7.aws`, db `AROGYAX`). Verified by reading it back — same `record_id` in both `screenings` and the `district_tier_trends` rollup. Destination bar for this leg ("real synced data, not sample rows") now met, not just the narrower Snowflake-only check from earlier. Two harmless synthetic rows left in Snowflake, matching the one left in Atlas — clear before a real demo. See ticket 018 |
| **App target changed 2026-08-30** | Flutter/Dart → native Android/Kotlin (ticket 019, user decision, reverses CLAUDE.md's prior "central architectural fact"). The ~3,900 lines of Dart never got compiled and now never will — kept as the module-by-module reference the Kotlin port is built against. Python mirrors are unaffected; they were always the verified side |
| Absent | `main.dart`/Kotlin equivalent, any UI, BLE, `SignalSource`, Tamil strings, replay traces — all still to be written, now directly in Kotlin |
| **Firmware flashed, boots clean on real hardware** | Real ESP32-D0WD-V3 (MAC `70:4b:ca:56:b1:10`) over COM7. Boot log confirmed live: I2C up, real MAX30102 answered, BLE advertising started, no crash/reset loop. BLE-visibility-to-a-phone confirmed via nRF Connect; PPG showing flat/low readings (open problem, not yet root-caused); AD8232 electrode signal still untested. See ticket 013 |
| Toolchain | **PlatformIO installed and proven.** Android Studio, Android SDK 34, Temurin 17 (for Gradle - the bundled JBR is JDK 25, too new for Gradle 8.9), Gradle 8.9 all installed and proven — `android/` builds and runs real tests. No `gradlew` committed yet (network blocks the wrapper task's URL validation specifically; direct downloads work fine) |
| **Kotlin port: modules 1, 2, 5, 8 done; 4 and 7 deliberately narrowed; 3 and 6 open** | Signal chain + PPG/fusion + record/patient-history + SignalSource fully ported. Explanation (4) ported against Policy's *types only*, not `decide()` — the boundary is named in `PolicyTypes.kt`'s header. Sync (7) narrowed to the pure backoff-ladder + queue-state-machine contract, not the real encrypted store or network client. A real PhysioNet AF recording (A00004) replayed through the real pipeline: SQI 0.934, irregularityScore **0.8136** (gate 0.5). Found `ppg_reference.py` had silently drifted from `ppg.dart` on two formulas — fixed at the source outside this session, confirmed byte-identical. `gradle testDebugUnitTest`: **58/58 passing**, 9 classes. See ticket 019 |
| **Spoken Tamil — all 39 strings have real audio now, all text unreviewed** | 6 tier/supporting strings as whole clips; all 33 `why.*` explanation reasons too, via user-directed Indian-Railways-style stitching: 23 fixed sentences as whole clips, 10 dynamic ones decomposed into fixed segments + a shared 0–200 Tamil number vocabulary (239 clips total, all real, all `ID3`-verified) composed at runtime by `ExplanationAudio.kt`. Every string, Tamil and English, is still AI-written and unreviewed — ticket 011 is unchanged in substance, just has a complete draft to react to. Actual playback (`MediaPlayer`, sequencing) still needs ticket 010's UI and a real device. `gradle testDebugUnitTest`: **65/65 passing**. See tickets 011, 015 |
| Hardware | ESP32 + AD8232 + MAX30102, real board now connected via USB (CP210x, Windows auto-driver). I2C side (MAX30102) proven; AD8232 electrode trace **still never produced** — that's ticket 003 |
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
- **Actian / n8n** — still past the destination. ~~Snowflake~~ — reopened 2026-08-30, now
  the second leg above; see tickets 017–018.
- **Field-level identity encryption** — real work, not on the route. Until built, don't claim it.
  Note this is a narrower claim than "the patient database is encrypted": the on-device
  store is SQLCipher whole-database encryption (built, see `app/lib/data/local_store.dart`)
  — what's out of scope here is encrypting individual identity *fields* beyond that.
- **Model retraining pipeline** — needs collected outcomes that don't exist yet.
- **Six-lead upgrade, SpO2.**
