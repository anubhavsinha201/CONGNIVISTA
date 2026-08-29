# Contract: decision policy and worker-facing text

**Status:** LOCKED at H0, written before any model existed. This is deliberate — the
policy is a product decision, not a byproduct of whatever the classifier happened to output.

---

## 1. The rule that governs everything below

**The output is referral urgency. It is never a condition name.**

The app must never render the words "atrial fibrillation", "AF", "arrhythmia", or any
diagnosis to the health worker or the patient. ArogyaX is a screening and triage layer;
a clinician makes every diagnosis (PRODUCT.md §1, §10).

Wrong: "AF detected"
Right: "Refer to PHC today — within 4 hours"

This is not legal caution. A WHV who tells a patient they have a heart condition has made
a diagnosis on our behalf, in a home, with no clinician present. The UI must make that
impossible to do accidentally.

---

## 2. Tier table

Evaluated top to bottom. First match wins. `history.isIntermittent` /
`history.isPersistent` are `PatientHistory` fields (`app/lib/data/patient_history.dart`),
computed from this patient's *prior* scored visits — never including the visit being
decided right now.

| # | Tier | Condition | Meaning |
|---|---|---|---|
| 1 | ⚪ **RETAKE** | `sqiScore` < 0.5 **or** `motionRejected` **or** `leadOffDetected` **or** BLE seq gap **or** `rrIntervalCount` < 30 | Not a result. Reposition and capture again. |
| 2 | 🔴 **RED** | irregularity high **and** (`meanHr` < 50 **or** `meanHr` > 120 **or** PPG corroborates — §7 of `ppg.md`) | Refer today, within 4 hours |
| 3 | 🟠 **ORANGE** | irregularity high, rate normal, no PPG escalation, **and** `history.isIntermittent` **or** `history.isPersistent` — **or** irregularity is *not* high this visit but `history.isIntermittent` | Refer within 24 hours — a pattern seen before, not a one-off |
| 4 | 🟡 **YELLOW** | irregularity high, rate normal, no PPG escalation, and no repeated-visit pattern | Refer within 48 hours — first time this has been flagged |
| 5 | 🟢 **GREEN** | none of the above | No rhythm concern today |

"irregularity high" = `rrIrregularityScore` ≥ 0.5, **or** `cnnScore` ≥ the calibrated
INT8 threshold when the CNN ran. Either path alone is sufficient to escalate.

### Why ORANGE can fire on a clean visit
Row 3's second clause is deliberate, not a typo: a visit whose *own* irregularity check
is clean can still come out ORANGE if `history.isIntermittent` — flagged on some past
visits, clean on others. That is the definition of a paroxysmal rhythm, and a single
clean 30-second window from a patient with that pattern is not the same evidence as a
clean window from a patient with no history at all (`docs/PRODUCT.md` §3's whole
longitudinal argument, `patient_history.dart`'s doc comment on `PatientHistory`).

`history.isPersistent` (every prior scored visit flagged) deliberately does **not** get
this same bypass. "Always flagged before, clean today" is read as a real result — possibly
a treatment response — not as evidence of a hidden episode, so it does not manufacture a
referral on its own. It still counts toward ORANGE on a visit that is *already* irregular
on its own terms (row 3's first clause).

### Why the two detectors are OR'd, not averaged
This is a **screening** instrument. The cost asymmetry is severe: a missed AF is a
preventable stroke that happens; a false positive is one unnecessary PHC visit that a
clinician resolves in minutes. We bias toward sensitivity and say so in the pitch.

### Why RETAKE is checked first and is a first-class outcome
A poor-contact trace scored anyway becomes a false referral, and false referrals are
exactly what discredit community screening programmes and get them shut down
(PRODUCT.md §5.4). Refusing to score is a feature. The UI shows RETAKE in a neutral
colour with a "what to fix" hint — never as an error or a failure.

### Why RED needs an abnormal rate (or PPG corroboration) as well as irregularity
Rate is what separates "get seen this week" from "get seen now." Irregularity with a
controlled ventricular rate is a referral; irregularity with a rate of 140 is a referral
that should not wait for market day. History never drives a visit to RED on its own —
only this visit's own rate or mechanical (PPG) evidence does, so the worst a repeated
pattern alone can produce is ORANGE.

### Why ORANGE is 24 hours and YELLOW is 48
Both are "irregularity flagged, rate normal, nothing mechanically corroborating it" —
the only difference is whether the pattern has been seen before. A repeated or
intermittent finding carries more weight than an isolated one, so it is asked to move
faster, short of RED's same-day urgency which stays reserved for rate/PPG evidence. The
exact hour counts are PROVISIONAL in the same sense §4's thresholds are — clinically
reasoned, not fitted — and are part of what ticket 011's clinician review must sign off.

---

## 3. Worker-facing strings

⚠️ **The Tamil below is DRAFT and requires review by a native Tamil speaker and a
clinician before any use with a real patient.** PRODUCT.md §5.5 commits to
clinician-reviewable templated text. These are templates awaiting that review — they are
adequate for the demo and must be marked as such if a judge asks.

All strings are static entries in a string table. **No text on this screen is ever
model-generated.**

| Tier | Tamil | English |
|---|---|---|
| RED | இன்றே ஆரம்ப சுகாதார நிலையத்திற்குச் செல்லவும் — 4 மணி நேரத்திற்குள். | Go to the PHC today — within 4 hours. |
| ORANGE | 24 மணி நேரத்திற்குள் ஆரம்ப சுகாதார நிலையத்தில் பரிசோதனை செய்யவும். இது முந்தைய வருகைகளிலும் தென்பட்டுள்ளது. | Get checked at the PHC within 24 hours. This has shown up on previous visits too. |
| YELLOW | இரண்டு நாட்களுக்குள் ஆரம்ப சுகாதார நிலையத்தில் பரிசோதனை செய்யவும். | Get checked at the PHC within two days. |
| GREEN | இன்று இதயத் துடிப்பில் சிக்கல் எதுவும் இல்லை. அடுத்த வருகையில் மீண்டும் பரிசோதிக்கவும். | No rhythm concern today. Recheck at the next visit. |
| RETAKE | சமிக்ஞை தெளிவாக இல்லை. மின்முனைகளைச் சரிசெய்து மீண்டும் பரிசோதிக்கவும். | Signal is not clear. Reposition the electrodes and test again. |

ORANGE's Tamil is a first DRAFT pass, not yet reviewed even at the level YELLOW/RED/
GREEN/RETAKE's drafts were — it is new text ticket 007 had to add, not a straight carry
of an existing reviewed line. Flagged for ticket 011.

Supporting line shown under every non-RETAKE result:

| Tamil | English |
|---|---|
| இது ஒரு பரிசோதனை மட்டுமே. நோய் கண்டறிதல் அல்ல. மருத்துவர் மட்டுமே முடிவு செய்வார். | This is a screening check only, not a diagnosis. Only a doctor decides. |

---

## 4. Thresholds in one place

Implemented as constants in `app/lib/core/policy.dart` so there is exactly one place to
tune them, and so the demo cannot drift from this document.

| Constant | Value | Set by |
|---|---|---|
| `kSqiGate` | 0.5 | C, tuned on real captures in Phase 1 |
| `kMinRrIntervals` | 30 | Fixed — the RR statistics are meaningless below this |
| `kMotionWanderRatioGate` | 0.35 (PROVISIONAL) | Inferred from ECG baseline wander — the MPU-6050 is no longer in the BOM |
| `kMotionPerfusionInstabilityGate` | 1.0 (PROVISIONAL) | Corroborated by PPG perfusion instability when a simultaneous capture exists |
| `kRrIrregularityGate` | 0.5 | C, tuned against MIT-BIH AFDB |
| `kCnnThresholdInt8` | TBD by D | **Refitted on INT8 scores**, never inherited from FP32 |
| `kHrLow` / `kHrHigh` | 50 / 120 | Fixed |

`kCnnThresholdInt8` is the whole differentiator (PRODUCT.md §6). It is filled in from
`ml/notebooks/quantization_calibration.ipynb` and nowhere else.
