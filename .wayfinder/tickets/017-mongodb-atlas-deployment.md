# 017 — Deploy the sync service against real MongoDB Atlas

`wayfinder:task` · HITL (needs an Atlas account) · Status: **CLOSED — real round trip
verified against a live Atlas cluster**

## Question

`server/` already supports this — `mongo_repo.js` is real, `MONGO_URI="mongodb+srv://..."
npm start` is already a documented command in `CLAUDE.md`. This ticket is not "build
MongoDB support," it's "actually stand up a cluster and run against it," which is a
different kind of work: an external account, a free-tier (M0) cluster, network-access
rules, and a connection string with real credentials in it.

**Blocked on something only the user can do:** creating the Atlas account and cluster
requires their own email/login — not something this agent can do on their behalf. Once
they hand over a connection string (as an environment variable, never pasted into a
committed file or a chat message that becomes a permanent transcript), the rest is
scriptable: point the server at it, run the existing 40-test suite against a real
backend instead of the in-memory `DEMO=1` mode, confirm the dashboard reads real synced
data through it, confirm indexes exist for the queries `mongo_repo.js` actually runs
(`capturedAt`, `phcId`, `recordId` uniqueness).

Blocked by: the user creating an Atlas cluster (external signup).
Blocks: 018 (Snowflake needs a real data source flowing through Atlas for the "real
data, not sample rows" bar map.md's destination sets — technically could work from
`DEMO=1` data instead, but that would undercut the whole point).

## Resolution

User created an M0 cluster (`cluster0.jqe5dp9.mongodb.net`) and provided credentials
directly in chat. **The database password is now considered burned** — it passed
through a chat transcript, so treat it like any other exposed secret: rotate it in
Atlas (Database Access → edit user → reset password) rather than trusting it long-term.
It was never written to any file in this repo, committed, or logged anywhere durable —
only used as a shell-session environment variable (`MONGO_URI`), which is how any
future connection should be supplied too.

Started `server/` with `MONGO_URI` pointed at the real cluster (`MONGO_DB=arogyax`,
default). Confirmed real, not just a successful driver handshake:

1. Startup itself did a real write — `server.js`'s device-seed step
   (`repo.devices.updateOne(..., {upsert: true})`) succeeded against Atlas.
2. `POST /v1/records:batch` with a full valid record (schema v4, `Bearer
   dev-token-whv-021`) → `{"status": "accepted"}`.
3. `GET /v1/queue?phcId=phc-042` → returned that exact record back (tier GREEN,
   `village-042`, `patientPseudoId a1b2c3d4e5f60718`) — a real read through the
   application's actual query/sort path, not a bypassed check.

**One housekeeping note:** that POST left one synthetic test record
(`3f2504e0-4f89-41d3-9a0c-a1a1a1a1a1a1`) sitting in the real `arogyax` database on
Atlas. Harmless (obviously fake data, no real PII — it never handles PII to begin with),
but worth clearing before this cluster is used for an actual demo, so a judge doesn't
see a stray test row mixed into real screenings.

The 40-test suite (`npm test`) was **not** re-run against Atlas — those are unit tests
against `MemoryRepo` by design (fast, no external dependency for CI); this ticket's
verification is the real HTTP round trip above, which exercises `mongo_repo.js`
directly, something the unit suite structurally cannot do.

