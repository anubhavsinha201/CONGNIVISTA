# 006 — Replace the motion gate with inferred motion

`wayfinder:task` · AFK · Status: **open — takeable now**

## Question

The MPU-6050 is dropped from the BOM, so `motionRejected` has no sensor behind it.
Re-derive it instead of removing it: baseline wander is already computed in
`app/lib/signal/sqi.dart`, and PPG perfusion instability (`ppg.dart`) is patient-side
motion — a better signal than an IMU would have been anyway, since the IMU lived on the
sensor unit, not the patient.

Touches ~19 files: `analysis.dart` (drops the `motionVarMilliG` parameter),
`policy.dart` (`kMotionVarGateMilliG` becomes a signal-derived threshold), `ble.md` §4
(status frame loses `accelVarMilliG`), `ppg.md` §2 (I²C table), `tiers.md`,
`record.schema.json`, plus the Dart tests and both Python validators
(`validate_policy.py`, `validate_ppg.py`).

**Two constraints, non-negotiable:**
1. The gate is safety-relevant — it may only be *re-derived*, never quietly weakened.
   `validate_policy.py` and `policy_test.dart` must still prove a moved capture yields
   RETAKE.
2. Docs must say **inferred**, not measured. `docs/PRODUCT.md` §5.3 currently claims a
   motion sensor rejects the trace; §8's BOM lists a part no longer in the unit. Both
   need correcting as part of this ticket, not left to drift further.

**Known, stated cost:** an IMU can separate "the patient moved" from "the electrode is
bad"; inference often cannot. That distinction is what §5.4's adaptive repeat promises
the worker, so retake hints must degrade honestly rather than guess a cause they can't see.

Blocked by: none for the code change — frontier. Threshold *tuning* is blocked by 003's
disturbed-trace capture (tracked in map's "Not yet specified").
Blocks: 013 (ESP32 firmware — settles the BLE frame layout).
