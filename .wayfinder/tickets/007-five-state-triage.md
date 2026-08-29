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
