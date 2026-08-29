# 018 — Snowflake analytics leg (district-level reporting)

`wayfinder:task` · HITL (needs a Snowflake account) · Status: **blocked**

## Question

`docs/PRODUCT.md` §9's roadmap already names this ("district-level risk analytics") —
this ticket pulls it forward, deliberately reopening what `map.md` had marked out of
scope, for track-prize eligibility rather than because the round trip needed it
(logged in map.md's Destination section, 2026-08-30).

**Contract before code, same rule as everything else in this repo.** This needs a new
`contracts/analytics.md` before implementation starts, covering at minimum:

- **Exactly what gets exported** — screening/referral records only. `patientPseudoId`
  already contains no PII (salted, on-device, contracts/record.schema.json), so the
  export inherits that property for free *if nothing else gets added along the way*.
  The contract should say this explicitly and name the exact field list, not leave it
  implicit — non-negotiable 5 ("No PII leaves the device") doesn't get a pass just
  because this leg is new.
- **Export mechanism and cadence** — a scheduled job pulling from MongoDB Atlas
  (ticket 017) into a Snowflake table. Simplest honest version: a small Node or Python
  script run on a schedule (cron, or a Snowflake task), not a heavyweight pipeline tool
  — matches this repo's existing preference for boring, inspectable infrastructure over
  new frameworks.
- **What "district-level reporting" actually means as a query** — counts by tier, by
  village code, over time, is the minimum bar map.md's destination sets. A dashboard
  view is a stretch goal, not the bar.

## Scope notes

- `villageCode` already exists in the schema (ticket 008) — this is the natural
  grouping key, already privacy-conscious (a code, not free-text locality).
- Keep this additive: nothing about the phone, the sync service's existing behavior, or
  the PHC dashboard should change because this leg exists. It reads from MongoDB; it
  does not sit in front of anything.

Blocked by: 017 (needs real data in Atlas to export from), a Snowflake account (external
signup, same class of blocker as 017's Atlas account).
Blocks: nothing — this is the new destination's last leg.
