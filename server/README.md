# arogyax-sync

The HTTPS boundary between a WHV's handset queue and MongoDB.
Wire protocol: [contracts/sync.md](../contracts/sync.md). Payload:
[contracts/record.schema.json](../contracts/record.schema.json) v2.

## Why this service exists

MongoDB has no supported path from a Flutter client to a cluster. Atlas Device
Sync, the Realm / Atlas Device SDKs (Flutter included), and the Atlas Data API
with its custom HTTPS Endpoints **all reached end-of-life on 30 September
2025**. MongoDB's own migration guidance is to run a self-managed API against
the native driver.

The one remaining shortcut — the pure-Dart `mongo_dart` driver dialling the
cluster straight from the handset — needs database credentials inside the APK
and an Atlas IP allowlist opened to `0.0.0.0/0`. For a store of health records
that is not a shortcut.

So this owns the three things a handset must not: the database credential, the
idempotent upsert, and the referral acknowledgement.

## Run it

```bash
npm install

# no database needed — everything in memory, same routes and responses
DEMO=1 npm start

# or against local MongoDB
docker compose up -d
npm start

# or against Atlas
MONGO_URI="mongodb+srv://…" npm start

npm test          # 33 tests, no database required
```

Listens on `:8787`. Health check at `/healthz`.

## Endpoints

| Method | Path | Auth | Who calls it |
|---|---|---|---|
| `POST` | `/v1/records:batch` | device bearer token | handset, ≤25 records |
| `GET` | `/v1/acks?since=` | device bearer token | handset |
| `GET` | `/v1/queue?phcId=` | none | dashboard |
| `POST` | `/v1/acks` | none | dashboard |

## Design notes worth knowing before you change something

**Per-record results, not a batch status.** `POST /v1/records:batch` returns one
`accepted` / `rejected` / `retryable` per submitted record, and the device marks
its queue from those and nothing else. An HTTP 200 on the batch is never itself
an acknowledgement — a record the response failed to mention stays pending.

**A database failure downgrades acceptance to `retryable`.** Never tell a device
"synced" for a record that was not written. That drops a referral silently,
which is the worst thing this service can do.

**Identity comes from the token.** `whvId` is read from the `devices` collection,
never from the payload, and a payload that disagrees is a 403. Otherwise one
compromised handset can forge referrals for every worker in the district.

**Server-owned fields are stripped on ingest.** `referralState`,
`referralUpdatedAt` and `referralUpdatedBy` are removed from every upload before
storage. A phone offline for six hours holds a stale copy, and letting its retry
write that back would silently revert an acknowledgement a nurse made an hour
ago. The device also omits them; this is the half that does not depend on the
client behaving.

**The ack vocabulary is a closed enum and must stay one.** There is no note
field. Worker-facing text comes from the app's static string table, and free
text arriving from outside is exactly the channel through which a diagnosis word
would reach a health worker's screen — which this product never displays.

**The schema is read from `contracts/`, not vendored.** One copy, three
consumers: the Dart record class, `src/validate.js`, and
`ml/reference/validate_record.py`.

## Known gaps

- **Dashboard endpoints are unauthenticated.** Fine on a PHC's internal network,
  not fine on the open internet. `GET /v1/queue` exposes pseudonymised records
  and `POST /v1/acks` is an unauthenticated write. Put this behind PHC staff
  auth before it leaves a LAN.
- **No rate limiting.** A device token is currently unbounded in request volume.
- **TLS is terminated by the host.** There is no in-payload encryption; the
  bearer token is only as safe as the transport carrying it.
- **The seed device token is a fixture**, printed at startup so it can never be
  mistaken for a production credential. Real deployments issue one per handset.
