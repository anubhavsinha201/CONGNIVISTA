# 005 — Refit the beat-timing thresholds

`wayfinder:research` · AFK · Status: **open — takeable now**

## Question

The RR-irregularity rule fires on ~50% of healthy recordings (measured: Sp 0.497 at the
deployed operating point), because the logistic centres in `app/lib/signal/rr_features.dart`
(`nRmssdCentre = 0.08`, `pnn50Centre = 0.30`, `entropyCentre = 0.65`) are literature values
that were never fitted to data. This is the same error class the INT8 calibration work
exists to correct, present in our own rule detector.

Refit against MIT-BIH AFDB, already downloaded at `ml/data/afdb.zip` (23 records, exact
AF episode boundaries, native 250 Hz — exactly suited to this job, per `CLAUDE.md`'s
"AFDB tunes the rules, CinC trains the CNN" split).

Resolved directly via `ml/reference/dsp_reference.py` + a new fitting script, not via a
`/research` subagent — this is local numerical work, not third-party documentation
lookup.

Blocked by: none — frontier.
Blocks: better numbers for 007 (five-state triage)'s YELLOW/ORANGE calibration.
