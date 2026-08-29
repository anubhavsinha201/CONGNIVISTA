# 017 — Deploy the sync service against real MongoDB Atlas

`wayfinder:task` · HITL (needs an Atlas account) · Status: **open — takeable once the
user provides a connection string**

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
