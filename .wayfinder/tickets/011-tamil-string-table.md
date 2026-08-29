# 011 — Tamil string table

`wayfinder:prototype` · HITL · Status: **blocked**

## Question

Cover all 31 keys in `app/lib/core/explanation.dart`'s `kExplanationKeys` plus the five
tier strings from ticket 007. Add a test asserting the table is complete — a missing key
is a blank line on a health worker's screen at a doorstep, discovered in the field rather
than in CI.

Reviewed by the Tamil-speaking teammate before use with a real patient (the existing
draft strings in `contracts/tiers.md` are marked DRAFT for exactly this reason).

Blocked by: 007 (Five-state triage — needs the final tier set and strings).
