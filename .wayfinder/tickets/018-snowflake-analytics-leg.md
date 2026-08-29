# 018 — Snowflake analytics leg (district-level reporting)

`wayfinder:task` · HITL (needs a Snowflake account) · Status: **closed — live round trip
verified against the real account, 2026-08-30**

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

## Progress (2026-08-30)

017 closed, unblocking this one. `contracts/analytics.md` written per the three bullets
above: exact field list (12 columns, `whvId`/raw signal fields/`lat`/`lon` explicitly
excluded), stage-then-merge export mechanism, `district_tier_trends` view for the
counts-by-tier-by-village-by-day query.

Code follows the contract: `server/src/snowflake_repo.js` (schema + bulk array-bind
insert into a staging table + one `MERGE` keyed on `record_id`, same idempotency
discipline as `contracts/sync.md` §5), `server/scripts/export_to_snowflake.js` (reads
`MongoRepo`, writes `SnowflakeRepo`, `--since` for incremental runs). 7 new tests against
a fake connection (no live account needed for these, same pattern `MemoryRepo` uses for
`mongo_repo.js`) — 47/47 passing.

User provisioned a real trial account: warehouse `AROGYA_WAREHOUSE`, database `AROGYAX`,
schema `PUBLIC`, account identifier derived from the console URL —
`ed06748.ap-southeast-7.aws`, confirmed correct on first connection attempt.

**Password handling — logged, not hidden.** This password was pasted into chat once
(same class of exposure as the Atlas password in ticket 017) and flagged for rotation.
User explicitly declined rotation and directed it be used as-is for this verification
("I havent, No need to do so, I want you to have access and complete the work
autonomously") — a deliberate call on their own $400-trial account, not a default. It was
passed only as a shell-local `SNOWFLAKE_PASSWORD` env var for one command, never written
to a file or committed. Still true: it remains a credential that has appeared in a chat
transcript, so the exposure ticket 017 named for Atlas applies here too, by the user's own
informed choice to accept it rather than rotate.

**Verified 2026-08-30:** a real write and a real read through the actual application
code — `SnowflakeRepo.mergeScreenings()` then a query against both `screenings` and
`district_tier_trends` — against the live account, not a driver handshake. One
obviously-synthetic record (`verify-018-<timestamp>`, `village-042`/`GREEN`), same as
the synthetic record ticket 017 left in Atlas; harmless, worth clearing before a real
demo. The one-off verification script used to run this was deleted after — not part of
the shipped path.

**Gap closed, 2026-08-30, same session as ticket 017's Mongo work.** With both
`MONGO_URI` (real Atlas cluster) and the Snowflake credentials above held as shell env
vars, ran the real thing: `npm run export:snowflake`. Log confirms a genuine Snowflake
connection and auth (`connection established successfully after 2189.84 milliseconds`,
`authentication successful using: SNOWFLAKE`), then `merged 1 screening (full export)`.

Verified by reading it back directly: `screenings` now contains
`record_id 3f2504e0-4f89-41d3-9a0c-a1a1a1a1a1a1`, `patient_pseudo_id a1b2c3d4e5f60718` —
**the exact record ticket 017 POSTed into MongoDB Atlas through the real
`/v1/records:batch` → `/v1/queue` path**, now pulled through by
`export_to_snowflake.js`'s own Mongo read, not hand-built. `district_tier_trends`
correctly rolled it into a `village-042`/`GREEN`/2026-08-30 row. This is map.md's exact
destination bar for this leg — "queryable in Snowflake from real synced data, not sample
rows" — now genuinely met, not the narrower version noted above.

Two harmless synthetic rows now sit in the real `screenings` table (this one, plus the
earlier hand-built `verify-018-...` row) — both worth clearing before an actual demo,
alongside ticket 017's matching test record in Atlas.
