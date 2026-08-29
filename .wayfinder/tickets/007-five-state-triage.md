# 007 — Five-state triage

`wayfinder:task` · AFK · Status: **open — takeable now**

## Question

Implement GREEN / YELLOW / ORANGE / RED + RETAKE, reconciling the two source documents
(both specify four triage levels) with the code (which shipped RED/AMBER/GREEN/RETAKE).

YELLOW = borderline or first-time abnormality. ORANGE = repeated across visits — which
`app/lib/data/patient_history.dart` already computes (`isIntermittent`, `isPersistent`,
`worstTier`), so this ticket's real work is wiring that history into `Policy.decide`
rather than inventing new logic.

Touches: `contracts/tiers.md`, `contracts/record.schema.json` (tier enum),
`app/lib/core/policy.dart`, `ml/reference/validate_policy.py`, `app/test/policy_test.dart`,
`dashboard/index.html` (tier colours/labels).

Blocked by: none — frontier.
Blocks: 011 (Tamil string table — needs the five tier strings), dashboard tier rendering.

## Note from ticket 005 (refit beat-timing thresholds)

The rules detector's own specificity is now well-calibrated (Sp 0.497→0.848 on CinC 2017,
cross-dataset confirmed). But the currently-deployed `RULES OR CNN` combination barely
moved (Sp stayed ~0.42), because it was already bottlenecked by the **CNN's own Sp 0.460**
on this population, not by the rules detector. Fixing rules removed its contribution to
the false-positive pool, but the CNN independently flags nearly the same windows anyway.

Worth considering as part of this ticket: whether ORANGE (repeated-across-visits) should
be reachable via a path that doesn't route through the current OR gate at all — since
`PatientHistory.isIntermittent`/`isPersistent` already look across visits, a single-visit
GREEN followed by a later flagged visit might deserve ORANGE even where the OR combination
alone wouldn't fire. Separately, the CNN's own INT8 operating point (`Policy.kCnnThresholdInt8`)
may need its own revisit — that's a distinct, already-documented problem, not something
this ticket should silently absorb without calling it out as its own decision.
