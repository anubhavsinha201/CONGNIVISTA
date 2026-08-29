# 011 — Tamil string table

`wayfinder:prototype` · HITL · Status: **blocked on human review — a draft now exists to
review, which didn't before**

## Question

Cover all 31 keys in `app/lib/core/explanation.dart`'s `kExplanationKeys` plus the five
tier strings from ticket 007. Add a test asserting the table is complete — a missing key
is a blank line on a health worker's screen at a doorstep, discovered in the field rather
than in CI.

Reviewed by the Tamil-speaking teammate before use with a real patient (the existing
draft strings in `contracts/tiers.md` are marked DRAFT for exactly this reason).

Blocked by: 007 (Five-state triage — needs the final tier set and strings). 007 closed
long ago; the real remaining blocker is the human review itself.

## Progress (2026-08-30)

`android/app/src/main/assets/strings/tamil_strings_DRAFT.json` now covers all 33 keys in
`EXPLANATION_KEYS` (`Explanation.kt`, the Kotlin port of `kExplanationKeys`) plus the 5
tier strings and the supporting line. The 6 tier/supporting strings are carried over
verbatim from `contracts/tiers.md`'s own pre-existing DRAFT (already marked as needing
review before this ticket existed). **The 33 explanation strings are new** — nothing
covered them anywhere in the repo before this, in either language.

**This is a draft to review, not a submission for this ticket's closure.** Every string
was written by an AI assistant, not a native Tamil speaker or a clinician — the file's
own `_comment` says so, in the file itself, not only here. Filename includes `_DRAFT` on
purpose. Still true regardless of how fluent it reads: CLAUDE.md non-negotiable 7 and
this ticket's whole reason for existing are both about who verifies clinical wording
before it reaches a worker, not about whether an AI can produce plausible Tamil.

What changed in practice: this ticket used to have nothing to react to. Now there's a
concrete draft a reviewer can correct line by line instead of starting from a blank page
— genuine progress toward closing this ticket, not a way to skip the closing itself.
