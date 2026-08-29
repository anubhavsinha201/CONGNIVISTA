# 016 — Reconcile the CNN threshold with the retrained model

`wayfinder:task` · AFK · Status: **CLOSED**

## Question

While checking whether the model was ready for hardware testing, `ml/evaluate.py`'s CNN
numbers (Sp 0.460) and `ml/artifacts/calibration_summary.json`'s CNN numbers (Sp 0.810)
disagreed sharply at the *same* threshold, read from the *same* saved scores file. Was
this a bug in one of the two scripts, or something else?

## Investigation

File mtimes settled it. `ml/artifacts/af_int8_seed0.tflite` and `scores_seed0.npz` were
regenerated on 2026-08-29 12:15 — the seed-0 model was retrained at some point outside
any wayfinder ticket (seeds 1–4 are untouched from 2026-08-28). Full-integer quantisation
is not bit-reproducible run to run, so the new model has different weights despite the
same seed and architecture. `ml/calibrate_threshold.py` was never re-run afterward, so:

- `Policy.kCnnThresholdInt8 = 0.007812` was a threshold fit for a model that no longer
  existed on disk.
- `app/assets/models/af_int8.tflite` (the actual bundled asset) still held the *old*
  model — same byte size as the new artifact, different content entirely.
- `ml/evaluate.py`'s Sp 0.460 was real: it correctly measured the *new* model at the
  *old*, now-wrong threshold. Not a worse model — a threshold silently carried over
  across a model change, which is exactly the failure `docs/PRODUCT.md` §6 exists to
  name, reproduced by accident inside this project's own artifacts directory.

## Resolution

1. Re-ran `ml/calibrate_threshold.py` — new fitted threshold for the current seed-0
   model: **0.1875** (was 0.007812). At this threshold: Se=0.911, Sp=0.804, close to the
   old model's own operating point (Se=0.919, Sp=0.810) — the *model* isn't meaningfully
   worse, only the stale threshold made it look catastrophic.
2. Updated `Policy.kCnnThresholdInt8` (with a doc-comment explaining the drift, not just
   the new number), `ml/evaluate.py`'s mirrored constant, and re-ran `ml/evaluate.py` for
   final self-consistent numbers.
3. **Found a second, older, unrelated gap while touching this:** `ml/reference/validate_policy.py`
   had `K_CNN_THRESHOLD_INT8 = None` — the pre-calibration placeholder — and had *never*
   been updated even back when the original threshold was first calibrated. Its
   "CNN ignored while uncalibrated" test was silently testing a state that stopped being
   true long before this session. Fixed, and replaced with five real tests of the
   calibrated path (mirrored into `app/test/policy_test.dart`, which had the identical
   gap: a permanently-`skip`'d test with no calibrated-path replacement).
4. **Fixed a chart bug this investigation surfaced**: `calibrate_threshold.py`'s panel 1
   had a hardcoded `set_xlim(0, 0.10)`, tuned for the old threshold. It silently clipped
   the new 0.1875 threshold off the visible axis — the chart still rendered without
   error, showing a threshold that no longer existed. Made the axis range dynamic.
5. Re-synced `app/assets/models/af_int8.tflite` to the current `ml/artifacts/af_int8_seed0.tflite`.
6. Rewrote `CLAUDE.md`'s "Current state" table, which was stale on every row (predating
   both ticket 005's rules refit and this threshold fix).

**Combined effect, CinC 2017 record-disjoint, `ml/evaluate.py`:**

| Detector | Se | Sp | PPV | F1 |
|---|---|---|---|---|
| Rules only (refit) | 0.750 | 0.848 | 0.330 | 0.458 |
| CNN only (refit threshold) | 0.911 | 0.804 | 0.317 | 0.471 |
| Rules OR CNN (deployed) | 0.952 | 0.706 | 0.244 | 0.389 |

Versus the project's original numbers (Sp 0.497 rules / Sp 0.429 combined), this is real,
measured progress: at 5.1% field prevalence, false alarms per 100 screened drop from
~54 to **~28**, while sensitivity stays high (0.952, vs. missing 0.2 AF cases per 100).

Verified: 5/5 Python validators green (dsp 30, policy **35** [+4 net], ppg 28, history 29,
record all), 40/40 server tests unaffected.

**Standing question for the user, not resolved here:** why was the seed-0 model retrained
outside the wayfinder process, and should the other four seeds be retrained to match for
consistency, or left as the cross-seed variance baseline they currently serve as?
