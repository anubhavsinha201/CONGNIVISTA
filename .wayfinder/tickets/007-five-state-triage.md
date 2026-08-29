# 007 — Five-state triage

`wayfinder:task` · AFK · Status: **CLOSED** · commit `31b1db8`

## Question

Implement GREEN / YELLOW / ORANGE / RED + RETAKE, reconciling the two source documents
(both specify four triage levels) with the code (which shipped RED/AMBER/GREEN/RETAKE).

YELLOW = borderline or first-time abnormality. ORANGE = repeated across visits — which
`app/lib/data/patient_history.dart` already computes (`isIntermittent`, `isPersistent`,
`worstTier`), so this ticket's real work is wiring that history into `Policy.decide`
rather than inventing new logic.

Touches: `contracts/tiers.md`, `contracts/record.schema.json` (tier enum),
`app/lib/core/policy.dart`, `ml/reference/validate_policy.py`, `app/test/policy_test.dart`,
`dashboard/index.html` (tier colours/labels).

Blocked by: none — frontier.
Blocks: 011 (Tamil string table — needs the five tier strings), dashboard tier rendering.

## Note from ticket 005 (refit beat-timing thresholds)

The rules detector's own specificity is now well-calibrated (Sp 0.497→0.848 on CinC 2017,
cross-dataset confirmed). But the currently-deployed `RULES OR CNN` combination barely
moved (Sp stayed ~0.42), because it was already bottlenecked by the **CNN's own Sp 0.460**
on this population, not by the rules detector. Fixing rules removed its contribution to
the false-positive pool, but the CNN independently flags nearly the same windows anyway.

Worth considering as part of this ticket: whether ORANGE (repeated-across-visits) should
be reachable via a path that doesn't route through the current OR gate at all — since
`PatientHistory.isIntermittent`/`isPersistent` already look across visits, a single-visit
GREEN followed by a later flagged visit might deserve ORANGE even where the OR combination
alone wouldn't fire. Separately, the CNN's own INT8 operating point (`Policy.kCnnThresholdInt8`)
may need its own revisit — that's a distinct, already-documented problem, not something
this ticket should silently absorb without calling it out as its own decision.

## Resolution

Implemented as scoped: `Tier` is now `retake / red / orange / yellow / green`, evaluated
top to bottom per `contracts/tiers.md` §2. RED and GREEN's own conditions are unchanged.
The old single AMBER split into two based on `PatientHistory`:

- **YELLOW** — irregularity flagged this visit, rate normal, no PPG escalation, no
  repeated-visit pattern behind it. What AMBER used to mean.
- **ORANGE** — the same, but `history.isIntermittent` or `history.isPersistent` is true
  (flagged on prior visits too, not a one-off).

**The bypass-the-gate question was decided, not deferred**, matching the ticket's own
framing that this one (unlike the CNN threshold) was in scope: a visit whose *own*
irregularity check is clean can still come out ORANGE when `history.isIntermittent`.
Reasoning: intermittent is, by definition, flagged on some visits and clean on others —
the paroxysmal signature `docs/PRODUCT.md` §3's whole longitudinal argument exists to
catch. A clean 30-second window from a patient with that established pattern is not the
same evidence as a clean window from a patient with no history at all.

`isPersistent` deliberately does **not** get this same bypass. "Always flagged before,
clean today" reads as a real result — possibly a treatment response — not as evidence of
a hidden episode, so it does not manufacture a referral on its own. It still counts
toward ORANGE on a visit that is already irregular on its own terms. History never
reaches RED by itself; only this visit's own rate or PPG evidence can escalate that far,
so the worst a repeated pattern alone produces is ORANGE.

**Architecture note found while wiring this in, not anticipated by the ticket:**
`Policy.decide` cannot take a `PatientHistory` parameter directly. `patient_history.dart`
imports `record.dart`, which imports `policy.dart` — a `PatientHistory` dependency here
would be an import cycle. It would also break `EcgAnalyser.analyse`'s purity (CLAUDE.md:
"depends on nothing beyond `dart:math` and `dart:typed_data`") — that function has no
store to query and must answer only from the samples it was given. Resolved by adding
two plain booleans to `TierInputs` (`historyIntermittent`, `historyPersistent`,
default `false`) that a future caller computes from an already-loaded `PatientHistory`
before assembling the rest of the inputs — the same pattern every other `TierInputs`
field already follows. No such caller exists yet (the capture-flow screen that would load
history and call `EcgAnalyser` is ticket 010, not built); this ticket makes `Policy` and
`PatientHistory` ready for that wiring, not the wiring itself, since there is no capture
flow yet to wire into.

Added `DecidedBy.history` for the one case where neither detector flagged the visit and
history alone produced the tier — the field that lets a PHC see a referral's evidence
(`app/lib/signal/analysis.dart`'s `toRecordFields`, `record.schema.json`'s `decidedBy`
description) stay honest rather than silently crediting `rules`. `versionFor` maps it to
`kRulesVersion`, since no CNN contributed.

**Referral timing:** RED stays "today, within 4 hours." ORANGE is new at "within 24
hours," YELLOW keeps AMBER's old "within 48 hours." The 24h figure is this ticket's own
invention, reasoned the same way the rest of `contracts/tiers.md` §4's thresholds are —
a repeated finding should move faster than an isolated one, short of RED's rate/PPG-only
same-day urgency — and is marked PROVISIONAL alongside the rest of the DRAFT Tamil,
pending ticket 011's clinician review. `PatientHistory`'s adaptive-repeat schedule
(`recommendedRepeatDays`) got a matching `kIntervalAfterOrange = 21`, sitting between
RED's 14 and YELLOW's 45 (kept, renamed from `kIntervalAfterAmber`); `worstTier` and
`repeatReasonKey` gained an `ORANGE` case (`previous_repeated_finding`) alongside it.

**One real bug found and fixed while touching this, not anticipated by the ticket:**
`EcgAnalyser.toRecordFields`'s `switch (decision.decidedBy)` was exhaustive with no
`default`, so adding `DecidedBy.history` to the enum would have been a compile error the
moment ticket 002 first runs a Dart SDK against this file. Found and fixed by reading the
call site rather than by that future compile — same class of latent bug ticket 008
found in `record.dart`'s duplicate constructor argument.

Touched beyond the ticket's own list, once traced: `app/lib/signal/analysis.dart` (the
switch above), `app/lib/core/explanation.dart` (`why.source.history` and
`why.repeat.previous_repeated_finding` added to `kExplanationKeys` — now 33 keys, up from
31; ticket 011's scope grows by exactly these two plus the five tier strings),
`server/src/service.js` (`TIER_RANK`), `server/src/mongo_repo.js` (a stale comment),
`server/test/sync.test.js` (three `AMBER` fixtures renamed, the ordering test extended to
all five tiers), `dashboard/index.html` (tier colours/classes/ordering), `CLAUDE.md`
(non-negotiable 6's wording, the `flutter test --plain-name` example).

**Deliberately not done:** the CNN's own Sp 0.460 bottleneck (ticket 005's finding) —
flagged again here, still not folded in, per the ticket's own instruction not to silently
absorb it. `ml/reference/validate_ppg.py`'s PPG-fusion tests were renamed AMBER→YELLOW
only; they don't exercise history, since PPG fusion is evaluated after the tier is
already known and has no history dependency of its own.

Verified: 5/5 Python validators green (dsp 30, policy **31** [7 new], ppg 28, history
**29** [3 new], record all), 40/40 server tests green (33 existing + the ordering test
extended, no count change). **Dart itself remains unverified by a Dart SDK** — these
edits are checked via the Python mirror and by inspection, same caveat every ticket before
002 carries.

Pushed as `31b1db8` on `main`.
