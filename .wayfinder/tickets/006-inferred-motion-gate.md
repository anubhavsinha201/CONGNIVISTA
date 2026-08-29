# 006 — Replace the motion gate with inferred motion

`wayfinder:task` · AFK · Status: **CLOSED (code)** · commit `6e3381c` · threshold tuning still fog

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
Blocks: 013 (ESP32 firmware — settles the BLE frame layout). **Now unblocked on this leg**
— 013 still waits on 001 (toolchain) and 003 (hardware bring-up).

## Resolution

Motion re-derived rather than removed: OR of ECG baseline wander (`sqi.dart`, always
available) and PPG perfusion instability (`ppg.dart`, new — corroborates when a
simultaneous usable capture exists). Same sensitivity-biased OR pattern the two AF
detectors already use.

`EcgAnalyser.analyse()` now takes an optional `PpgResult` instead of the IMU's
`motionVarMilliG` list. **Zero Dart call sites needed updating** — `EcgAnalyser` is never
instantiated by any existing test, only referenced in comments, so this ticket's Dart
change has no test blast radius at all.

Contracts brought in phase: `ble.md`'s status frame shrinks 6→4 bytes, with the freed IMU
flag bit **reserved rather than reused** — so old firmware built against the prior
contract fails visibly instead of silently setting a bit nothing reads anymore. `ppg.md`
gains the stability signal and drops the two-device I²C table. `tiers.md`'s threshold
table replaces the single IMU constant with the two new ones. `docs/PRODUCT.md`'s three
motion-sensor claims (§3, §5.3, §8's BOM) corrected. Both new thresholds are marked
**PROVISIONAL** — physiologically reasoned, not fitted; retuning needs ticket 003's
disturbed-vs-still captures, which that ticket's scope was extended to collect.

**Two real bugs found and fixed while building this, both worth remembering:**

1. Computing perfusion index by independently re-filtering each 1-second sub-window
   produced severe filter-edge noise (a 0.5 Hz highpass needs seconds to settle), which
   made a perfectly *steady* synthetic capture read as **more unstable** (1.67) than the
   deliberately disturbed one (1.62) — the metric was measuring its own filtering
   artifact, not motion. Fixed by filtering the whole capture once, then windowing the
   already-filtered signal, in both `ppg.dart` and its Python mirror.
2. The first version of the disturbed-vs-steady test reused a pulse train with `synth_ecg`'s
   own natural start/end dead zones; splitting it into two halves compounded those gaps
   into spurious instability unrelated to the injected disturbance. Fixed by building a
   dedicated, fully-covering pulse train for this one test.

Verified: 5/5 Python validators green (dsp 30, policy 24, **ppg 28** [7 new], history 26,
record all), 40/40 server tests unaffected. Dart itself remains unverified by a Dart SDK.

Pushed as `6e3381c` on `main`.
