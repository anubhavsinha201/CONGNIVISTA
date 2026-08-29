# Contract: MongoDB → Snowflake analytics export

**Status:** DRAFT. Written before the export code, per this repo's contract-before-code
rule, but not yet run against a live account with real synced data — see
`.wayfinder/tickets/018-snowflake-analytics-leg.md`. Move to LOCKED once it has.

---

## 1. Why this exists, and why it is honest to say so

`docs/PRODUCT.md` §9 already names district-level risk analytics as a roadmap item. This
pulls it forward **for track-prize eligibility, not because the round trip needed it** —
logged in `.wayfinder/map.md`'s Destination section, 2026-08-30. Say so out loud the same
way the demo-integrity section of `CLAUDE.md` insists a replayed capture is announced as
replayed: this leg is additive, and nothing about the phone, the sync service, or the PHC
dashboard depends on it existing.

The genuine (not cosmetic) reason it's a reasonable thing to build anyway: `screenings` in
MongoDB is shaped for point queries — "this PHC's queue, sorted by tier" — not for
group-by-village-by-month rollups. That's what a warehouse is for, and Snowflake is a
better tool for that query than indexing Mongo harder would be.

---

## 2. What gets exported

Fields, exactly this list, read from `contracts/record.schema.json`:

`recordId`, `patientPseudoId`, `phcId`, `capturedAt`, `ageBand`, `villageCode`, `sex`,
`tier`, `referralState`, `clinicianOutcome`, `clinicianOutcomeAt`, `modelVersion`.

**Deliberately excluded**, and re-adding any of these silently is the failure this section
exists to name:

- `whvId` — identifies a specific health worker, not needed to count screenings by
  village. No purpose here, so it doesn't travel.
- `lat`, `lon`, `locationAccuracyM` — `villageCode` is the schema's own privacy-conscious
  grouping key, specifically *not* a precise fix (record.schema.json's own description of
  `villageCode`). Exporting the GPS point alongside it would undo that choice.
- Every raw signal field — `sqiScore`, `meanHr`, `rrIntervalCount`, `rrIrregularityScore`,
  `cnnScore`, `decidedBy`, all `ppg*`/fusion fields, `ecgDurationSec`, `ecgWaveformRef`.
  None of them are inputs to "counts by tier, by village, over time," which is the whole
  of what this leg exists to answer (`.wayfinder/map.md` Destination section).

**No new PII risk.** `patientPseudoId` is already a salted, on-device hash before it ever
reaches Mongo (non-negotiable 5) — this export inherits that property for free precisely
because it adds no field that isn't already in the pseudonymised set.

---

## 3. Mechanism and cadence

A standalone script, `server/scripts/export_to_snowflake.js`, run manually or by an
external scheduler (cron, or a Snowflake Task) — **not** triggered by the sync service and
not a live stream. A screening is already sync-when-convenient, never on the critical path
(non-negotiable 4, `contracts/sync.md` §2); there is no case here for a real-time pipe.

Each run:

1. Reads every `screenings` document from MongoDB matching the export field list above
   (optionally `--since <ISO-8601 capturedAt>` for an incremental run).
2. Bulk-loads them into a `SCREENINGS_STAGING` table in one round trip (array bind insert,
   not one `INSERT` per row).
3. Runs one `MERGE INTO screenings ... ON record_id` from staging into the fact table.

Stage-then-merge instead of row-at-a-time is the efficient shape for this workload, not
just the simple one: Snowflake bills for warehouse-seconds a query runs, so N tiny writes
cost more compute than one bulk load plus one merge, for the same rows.

### Idempotency

The merge is keyed on `record_id`, same discipline as `contracts/sync.md` §5's
`updateOne({ recordId }, { $set }, { upsert: true })` on the Mongo side. A re-run of the
export — after a partial failure, or just run twice — updates existing rows in place
rather than double-counting a village's screenings.

---

## 4. District-level view

`district_tier_trends`, a plain SQL view over `screenings`:

```sql
CREATE OR REPLACE VIEW district_tier_trends AS
SELECT village_code, tier, DATE_TRUNC('day', captured_at) AS day, COUNT(*) AS screenings
FROM screenings
GROUP BY village_code, tier, day;
```

Doing the rollup as a view keeps the export script dumb — it only ever lands fact rows —
and lets Snowflake do the one thing it's actually good at. This view, queried, is the
`.wayfinder/map.md` destination bar for this leg: "counts by tier, by village, over time,"
queryable from real synced data, not sample rows.

---

## 5. Schema

```sql
CREATE TABLE IF NOT EXISTS screenings (
  record_id            STRING PRIMARY KEY,
  patient_pseudo_id     STRING,
  phc_id                STRING,
  captured_at           TIMESTAMP_TZ,
  age_band              STRING,
  village_code          STRING,
  sex                   STRING,
  tier                  STRING,
  referral_state        STRING,
  clinician_outcome     STRING,
  clinician_outcome_at  TIMESTAMP_TZ,
  model_version         STRING
);
```

Database and schema are wherever the account provisions them (this build: database
`AROGYAX`, schema `PUBLIC`) — not hard-coded into the contract, since which database holds
this is a deployment detail, not a decision about what data moves or how.

---

## 6. Credentials and connection

Same discipline as `MONGO_URI` (`contracts/sync.md` §4, `CLAUDE.md` ticket 017 note):
`SNOWFLAKE_ACCOUNT`, `SNOWFLAKE_USER`, `SNOWFLAKE_PASSWORD`, `SNOWFLAKE_WAREHOUSE`,
`SNOWFLAKE_DATABASE`, `SNOWFLAKE_SCHEMA` as environment variables only — never committed,
never logged, never pasted into a chat transcript that becomes a permanent record. A
credential that passes through chat is burned and must be rotated, same rule that applied
to the Atlas password in ticket 017.

---

## 7. What this must not touch

Additive only. The phone, the sync service's existing routes (`records:batch`, `acks`,
`queue`), and the PHC dashboard are unchanged by this leg existing. The export script is a
separate reader of the same MongoDB collection — it does not sit in front of anything, and
nothing in `server/src/routes.js` or `service.js` calls into it.

---

## 8. What is deliberately not here

- **No PII beyond what already reaches Mongo.** See §2.
- **No raw waveform or per-beat signal data.**
- **No real-time trigger.** Batch only, per §3.
- **No dashboard UI for this data in this build.** The destination bar is "queryable in
  Snowflake" (`.wayfinder/map.md`), not a rendered chart — a dashboard view is a stretch
  goal noted in ticket 018, not the bar this contract locks.
