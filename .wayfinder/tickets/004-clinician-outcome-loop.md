# 004 — Wire the clinician outcome loop

`wayfinder:task` · AFK · Status: **CLOSED** · commit `20bd8c1`

## Question

`POST /v1/acks` currently ignores `clinicianOutcome`; the dashboard's only control is a
Refresh button. `CLINICIAN_OUTCOMES` already exists in `server/src/validate.js`. Accept
the field through `routes.js` → `service.js`, and add confirm / not-confirmed /
inconclusive controls to `dashboard/index.html`.

This is the last leg of the destination's round trip (capture → ... → clinician records
an outcome → outcome returns to the phone), and today it is a stub: the field exists in
the schema, the Dart record, the local store, and the acks parser, but the server route
drops it on the floor.

No Flutter/Android toolchain required — pure Node + static HTML/JS.

Blocked by: none — frontier.
Blocks: nothing directly; closes out the destination's final leg once done.

## Resolution

Wired end to end: `service.js` → `memory_repo.js` + `mongo_repo.js` → `routes.js` →
a new independent outcome control on the dashboard (confirm / not-confirmed /
inconclusive), separate from the existing follow-up dropdown.

**The design question this ticket actually turned on:** `referralState` (process) and
`clinicianOutcome` (finding) are deliberately separate concerns, but they share one
endpoint. So a plain referral-state update must never silently erase an outcome a
clinician already recorded. Solved by distinguishing "key not present in this call"
(`undefined` — leave the outcome alone) from "key present" (set/overwrite it), carried
correctly from the HTTP body (`'clinicianOutcome' in body`) through the service and into
both repos' partial updates (`Object.assign`/`$set` only touch keys actually given).

Added 7 tests: round trip through acks, the non-clobbering guarantee proven at both the
service layer and over real HTTP (serialization is what actually produces "key absent"
on the wire — only an HTTP-level test can catch a regression there), validation,
`inconclusive` as its own non-negative value, and the no-diagnosis-string check extended
to this enum.

Verified: 40/40 server tests (33 existing + 7 new), 5/5 Python validators unaffected.

Pushed as `20bd8c1` on `main`.
