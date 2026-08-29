# Contract: offline queue and opportunistic sync

**Status:** LOCKED at H0+. Changing anything here requires telling both B (app) and E
(dashboard) out loud.

The app, the sync service, and the dashboard are built in parallel against this document.
The record payload itself is [record.schema.json](record.schema.json) v2 — this file
covers only how it moves.

---

## 1. Why there is a server at all

MongoDB has no supported path from a Flutter client to a cluster. Atlas Device Sync, the
Realm / Atlas Device SDKs (Flutter included), and the Atlas Data API with its custom HTTPS
Endpoints **all reached end-of-life on 30 September 2025**. MongoDB's own migration
guidance is to run a self-managed API against the native driver.

The one remaining shortcut — the pure-Dart `mongo_dart` driver dialling the cluster
straight from the handset — needs database credentials inside the APK and an Atlas IP
allowlist opened to `0.0.0.0/0`. For a store of health records that is not a shortcut, it
is a breach waiting to be written up.

So the boundary is HTTPS, and the server owns three things a handset must not: the
database credential, the idempotent upsert, and the referral acknowledgement.

```
phone                             coverage                cloud
drift + SQLCipher queue  ──POST /v1/records:batch──▶  Node + mongodb driver ──▶ Atlas
     (pending / synced)  ◀──GET  /v1/acks?since=───   (screenings collection)
                                                              ▲
                                    static dashboard ─────────┘  GET /v1/queue
                                                                 POST /v1/acks
```

---

## 2. The rule that outranks the rest

**Sync never blocks a result.** A screening completes, renders a tier, and is durable on
disk with the radio off (CLAUDE.md non-negotiable 4). The capture path calls
`LocalStore.insert` and returns; it never awaits `SyncEngine`. If the sync layer throws,
hangs, or is not running at all, the worker still gets an answer and the record still
survives a reboot.

Corollary: no endpoint here is on the critical path of care. Every one of them may be
down for the whole shift without a patient being affected.

---

## 3. Ownership of fields

Three-way split, and violating it corrupts data in ways that are hard to see.

| Owner | Fields |
|---|---|
| **Signal layer** | `ecgDurationSec`, `sqiScore`, `motionRejected`, `leadOffDetected`, `meanHr`, `rrIntervalCount`, `rrIrregularityScore`, `cnnScore`, `decidedBy`, `tier`, `modelVersion`, all `ppg*` and fusion fields |
| **Device / data layer** | `recordId`, `schemaVersion`, `patientPseudoId`, `whvId`, `phcId`, `capturedAt`, `lat`, `lon`, `locationAccuracyM`, `ecgWaveformRef`, `syncState`, `syncedAt` |
| **Server** | `referralState`, `referralUpdatedAt`, `referralUpdatedBy` |

The device **must not** send `referralState`, `referralUpdatedAt`, or `referralUpdatedBy`
in an upload, and the server **must not** apply them from one. A phone that has been
offline for six hours holds a stale copy; letting its retry write that copy back would
silently revert a PHC acknowledgement made an hour ago. The server strips them on ingest
rather than trusting clients to omit them.

`syncState` and `syncedAt` are device bookkeeping and are not stored server-side.

---

## 4. Authentication

Per-device bearer token, issued when a worker's phone is provisioned:

```
Authorization: Bearer <deviceToken>
```

Checked against the `devices` collection, which maps a token to its `whvId` and `phcId`.
**The server takes `whvId` from the token, not from the payload** — a device may only
write records attributed to itself. A payload `whvId` that disagrees with the token is a
`403`, not a silent overwrite.

Tokens are opaque random strings, stored on the phone in `flutter_secure_storage`. TLS is
terminated by the host; there is no in-payload encryption.

---

## 5. `POST /v1/records:batch`

Request — at most **25** records per batch, oldest first:

```json
{ "records": [ { /* record.schema.json v2 */ } ] }
```

Response is **per record**, and this shape is the whole point of the endpoint:

```json
{
  "results": [
    { "recordId": "…", "status": "accepted" },
    { "recordId": "…", "status": "rejected",  "code": "schema_invalid", "detail": "…" },
    { "recordId": "…", "status": "retryable", "code": "db_unavailable" }
  ]
}
```

| status | Device action |
|---|---|
| `accepted` | `syncState = synced`, stamp `syncedAt` |
| `rejected` | `syncState = failed`, no further retry — the record is malformed and retrying cannot fix it |
| `retryable` | stay `pending`, apply backoff (§7) |

A record absent from `results` is treated as `retryable`. **An HTTP 200 on the batch is
never itself an ack.** The device marks state from `results` and nothing else.

### Idempotency

The upsert is keyed on `recordId`:

```js
updateOne({ recordId }, { $set: fields }, { upsert: true })
```

`recordId` carries a unique index. A retry after an ack that was lost on the way back
therefore re-writes the same document rather than creating a second referral — which is
exactly what the `recordId` description in `record.schema.json` already promises. This is
the property that makes the rural connectivity pattern safe: signal appears for eight
seconds, some acks never arrive, and the next flush is harmless.

---

## 6. Acknowledgements, both directions

`GET /v1/acks?since=<ISO-8601>` — `whvId` comes from the token, never the query string.

```json
{ "acks": [ { "recordId": "…", "referralState": "seen_at_phc",
              "referralUpdatedAt": "…", "referralUpdatedBy": "phc-042" } ],
  "cursor": "2026-08-29T11:04:00+05:30" }
```

The device persists `cursor` and sends it as the next `since`.

`POST /v1/acks` — written by the dashboard, one state transition:

```json
{ "recordId": "…", "referralState": "patient_contacted", "referralUpdatedBy": "phc-042" }
```

`referralState` is validated against the enum in `record.schema.json`. **There is no note
field and none may be added.** Worker-facing text comes from the static string table
(non-negotiable 6), and free text arriving from outside is precisely the channel through
which the word "atrial fibrillation" would reach a worker's screen (non-negotiable 1). The
app renders each enum value through `strings_ta.dart`.

---

## 7. Backoff

Per record, on `retryable` or transport failure. `attemptCount` and `nextRetryAt` are
local-only columns, persisted, so the schedule survives the process being killed —
which on a battery-saving Android handset in a village is the normal case, not the edge
case.

| Attempt | Delay |
|---|---|
| 1 | 5 s |
| 2 | 30 s |
| 3 | 2 min |
| 4 | 10 min |
| 5 | 30 min |
| 6+ | 1 h (cap) |

Multiplied by uniform jitter in `[0.5, 1.5]`, so a van carrying four workers back into
coverage does not produce four synchronised retry storms.

A connectivity transition to online clears `nextRetryAt` and flushes immediately: the
backoff exists for a server that is refusing, not for an absent radio.

---

## 8. Triggers

The engine flushes on:

1. `connectivity_plus` transitioning to online,
2. app foreground,
3. a manual "sync now" from the worker,
4. a 5-minute timer, only while pending > 0.

Connectivity is a hint and never a precondition — the platform reports "online" for a
captive portal and for a tower that answers ARP and nothing else. The engine always
attempts and treats the result as truth.

---

## 9. Local retention

`pruneSynced(olderThan: 30 days)` on startup. Records still `pending` or `failed` are
**never** pruned regardless of age. An unsynced referral is the one thing on the device
that cannot be reconstructed.

---

## 10. Collection and indices

Database `arogyax`, collection `screenings`.

| Index | Purpose |
|---|---|
| `{ recordId: 1 }` **unique** | idempotency — the load-bearing one |
| `{ phcId: 1, tier: 1, capturedAt: -1 }` | dashboard referral queue |
| `{ whvId: 1, referralUpdatedAt: -1 }` | ack pull |

Collection `devices`: `{ token, whvId, phcId, provisionedAt }`, unique on `token`.

---

## 11. What is deliberately not here

- **No raw waveform upload.** `ecgWaveformRef` is a local path and stays local, as
  `record.schema.json` already states. Bandwidth and consent, neither settled.
- **No PII.** The payload key set is a subset of `record.schema.json` properties, enforced
  by a test, and `patientPseudoId` is the only patient-linked value in it (non-negotiable 5).
- **No delta/CRDT merge.** A screening is immutable once captured; the only mutable field
  is server-owned. There is nothing to conflict.
