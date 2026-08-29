# 004 — Wire the clinician outcome loop

`wayfinder:task` · AFK · Status: **open — takeable now**

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
