# 005 — Refit the beat-timing thresholds

`wayfinder:research` · AFK · Status: **CLOSED** · commit `18ac3fa`

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

## Resolution

Refit via genuine 1-D maximum-likelihood logistic regression against AFDB's 23 usable
records (23,620 clean 30 s windows), using the database's own `.qrs` reference beats and
`.atr` rhythm labels. New `ml/reference/tune_rr_thresholds.py`.

**Two things almost went wrong, both caught by checking rather than trusting the first
number:**

1. A single 17/6 split suggested the refit cost real sensitivity (0.994→0.805). 5-fold
   cross-validation showed that split was an unusually pessimistic outlier — the real
   result is Se 0.957±0.050 / Sp 0.911±0.073, against the old Se 0.998±0.002 /
   **Sp 0.702±0.169** (the old centres' specificity swung 0.48–0.88 depending on which
   patients were tested — that instability *is* the bug this ticket fixes).
2. A sensitivity-targeted gate (mirroring the INT8 calibration's own Se=0.90 target) hit
   its target exactly on training records and collapsed to Se 0.45–0.57 on held-out ones.
   With 23 patients a single percentile threshold doesn't generalize. **Shipped: gate
   stays at 0.5**, its principled unfit value — not a number chosen to flatter one split.

**Confirmed on CinC 2017 — a dataset entirely independent of what was fit on:** rules-only
Sp 0.497 → **0.848**, closely matching the AFDB CV estimate. That cross-dataset agreement
is real evidence the fit generalizes rather than being an AFDB-only artifact.

**Honest limit surfaced, not hidden, for ticket 007:** the deployed `RULES OR CNN` barely
moved (Sp 0.429 → 0.421) because its specificity was already bottlenecked by the CNN's own
Sp 0.460 on this population, not by the rules detector. Fixing rules removed its
contribution to the false-positive pool, but the CNN independently flags nearly the same
windows anyway. That's the INT8 operating point — a separate, already-documented problem.

Verified: 5/5 Python validators green, 40/40 server tests unaffected, `golden_vectors.json`
regenerated (4 fixtures' `irregularityScore` shifted, all still correctly gated).

Pushed as `18ac3fa` on `main`.
