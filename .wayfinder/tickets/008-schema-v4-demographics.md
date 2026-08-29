# 008 — Schema v4: age band and village

`wayfinder:task` · AFK · Status: **open — takeable now**

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
