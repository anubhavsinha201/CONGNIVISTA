# 008 — Schema v4: age band and village

`wayfinder:task` · AFK · Status: **CLOSED** · commit `8f4f857`

## Question

Add `ageBand` and `villageCode` as **required**; `sex`, `systolicBp`, `diastolicBp`, and
`glucose` as **optional and nullable** (per the locked decision: recommended fields
necessary, the rest optional). Age *band* rather than exact age, and village *code*
rather than free-text locality — keeps the record from becoming quasi-identifying on its
own, since clinical fields are stored unencrypted by design (`docs/PRODUCT.md` §11).

Bump `contracts/record.schema.json` and `ScreeningRecord.kSchemaVersion` to 4; add the
v3→v4 `local_store.dart` migration beside the existing v2→v3 step (nullable/defaulted
columns, so existing rows stay valid — same pattern as the last two migrations).

Blocked by: none — frontier.
Blocks: any future risk-engine work that wants age/geography as an input (fog item).

## Resolution

Done as scoped: `ageBand` (4 bands) and `villageCode` required; `sex`/`systolicBp`/
`diastolicBp`/`glucose` optional. Schema bumped to v4, with the v2→v3 history corrected
in the same pass (see below).

**Two pre-existing bugs found and fixed along the way**, both worth recording because
they were latent until this ticket touched the same code:

1. `record.dart`/`record.schema.json` were still pinned at `schemaVersion 2`, but
   `local_store.dart` had already migrated to `user_version 3` in an earlier session
   when `clinicianOutcome` was added. The version number never got bumped alongside it.
   Corrected: that gap is now documented as v3, and this ticket's work is v4.
2. `ScreeningRecord.fromJson` assigned `clinicianOutcome`/`clinicianOutcomeAt` as named
   constructor arguments **twice** — a duplicate-argument compile error. Would have
   surfaced the moment ticket 002 ran a Dart SDK against this file for the first time;
   found instead by reading the file for this ticket.
3. `ml/reference/validate_record.py`'s `SERVER_OWNED` set was missing
   `clinicianOutcome`/`clinicianOutcomeAt` entirely — the Python mirror was silently not
   checking that those fields never leak into an upload payload. Same drift class already
   fixed once in `server/src/validate.js`.

All touch points updated: schema, `record.dart` (fields/ctor/`fromAnalysis`/`copyWith`/
`toJson`/`fromJson`/`validate()`), `local_store.dart` (v3→v4 migration, insert columns,
`_fromRow`), five Dart test call sites across three test files, and both the Python and
JS validators + their test fixtures.

Verified: 5/5 Python validators green (dsp 30, policy 24, ppg 21, history 26, record all
41), 33/33 server tests green. **Dart itself remains uncompiled** — these edits are
verified via the Python mirror and by inspection, not by a Dart SDK. That's ticket 002.

Pushed as `8f4f857` on `main`.
