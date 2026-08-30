# 010 — Design the capture-to-result flow

`wayfinder:prototype` · HITL · Status: **prototype built, awaiting human pick**

## Question

What does the worker actually see, screen by screen, from opening the app to reading a
tier? "How should it behave" is the question, so this wants `/prototype`, not prose —
raise the fidelity of the discussion with something concrete to react to before writing
production UI code.

~~Blocked by: 002 (First compile of the Dart).~~ Stale — 002 was Dart-specific and the
app target moved to Kotlin (ticket 019). Nothing blocks this now; the Kotlin project
already compiles and its tests are green.

## Prototype (2026-08-30)

[android/scripts/prototype_capture_flow.html](../../android/scripts/prototype_capture_flow.html)
— static HTML/JS, no framework, matching `dashboard/index.html`'s no-build-step
convention. Serve with `cd android && python -m http.server 8080`, open
`http://localhost:8080/scripts/prototype_capture_flow.html`.

Three structurally different variants of the same six scenes (patient select → connect →
capturing → mid-capture interrupt → RETAKE → result), switchable with `?variant=A|B|C`
via the floating bottom bar (or `←`/`→`), with a scene picker on top:

- **A — full-screen wizard takeover.** One step fills the screen; the tier result is a
  full-colour wash (traffic-light read at a glance); RETAKE reuses the same takeover
  shape but in neutral grey-blue, never red/orange, so it can't be mistaken for urgency.
- **B — persistent status rail.** A thin top rail (BLE status, session pseudoId, sync
  state) stays visible across every screen; RETAKE and RESULT render as sibling card
  types in the same body area — tests whether RETAKE should feel like a variant of the
  same "session card" family rather than its own mode.
- **C — append-only conversational log.** Each step appends a bubble to a running
  transcript, WhatsApp-style; a RETAKE is just the next bubble ("this attempt didn't
  work, try again") rather than a dead-end screen — the most direct test of whether
  RETAKE reads as calmly as the non-negotiables intend.

Real content throughout, not lorem ipsum: Tamil/English text pulled live from
`tamil_strings_DRAFT.json`, and every "🔊 listen" button plays real audio resolved with
the same logic as `ExplanationAudio.kt` (segments + number vocabulary, correctly
clamped). Result scenarios are grounded in real `why.*` reasons — ORANGE exercises the
repeat-visit-history reason (`why.history.flagRate` + `why.repeat.previous_repeated_finding`),
RED exercises the PPG-escalation reason (`why.rate.high` + `why.ppg.pulseDeficit`) shown
inline rather than as its own verdict, per `contracts/ppg.md` §7.

**Open questions for the human pick:** does RETAKE deserve its own full-screen mode (A),
a sibling-card treatment (B), or a same-stream log entry (C)? Does the full-colour tier
wash in A read clearly to a non-clinician, or does B's smaller badge undersell urgency?
Mixing is expected and fine — "the header from B with the body from C" is a valid answer.

Throwaway by design — once a direction is picked, fold the decision into real Kotlin UI
code (Compose or Views, still an open choice) and move this file to a throwaway branch
per the prototype skill, not into the production app module.
