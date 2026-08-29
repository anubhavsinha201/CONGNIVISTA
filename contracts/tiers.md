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

Evaluated top to bottom. First match wins.

| # | Tier | Condition | Meaning |
|---|---|---|---|
| 1 | ⚪ **RETAKE** | `sqiScore` < 0.5 **or** `motionRejected` **or** `leadOffDetected` **or** BLE seq gap **or** `rrIntervalCount` < 30 | Not a result. Reposition and capture again. |
| 2 | 🔴 **RED** | irregularity high **and** (`meanHr` < 50 **or** `meanHr` > 120) | Refer today, within 4 hours |
| 3 | 🟡 **AMBER** | irregularity high, `meanHr` 50–120 | Refer within 48 hours |
| 4 | 🟢 **GREEN** | none of the above | No rhythm concern today |

"irregularity high" = `rrIrregularityScore` ≥ 0.5, **or** `cnnScore` ≥ the calibrated
INT8 threshold when the CNN ran. Either path alone is sufficient to escalate.

### Why the two detectors are OR'd, not averaged
This is a **screening** instrument. The cost asymmetry is severe: a missed AF is a
preventable stroke that happens; a false positive is one unnecessary PHC visit that a
clinician resolves in minutes. We bias toward sensitivity and say so in the pitch.

### Why RETAKE is checked first and is a first-class outcome
A poor-contact trace scored anyway becomes a false referral, and false referrals are
exactly what discredit community screening programmes and get them shut down
(PRODUCT.md §5.4). Refusing to score is a feature. The UI shows RETAKE in a neutral
colour with a "what to fix" hint — never as an error or a failure.

### Why RED needs an abnormal rate as well as irregularity
Rate is what separates "get seen this week" from "get seen now." Irregularity with a
controlled ventricular rate is a referral; irregularity with a rate of 140 is a referral
that should not wait for market day.

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
| AMBER | இரண்டு நாட்களுக்குள் ஆரம்ப சுகாதார நிலையத்தில் பரிசோதனை செய்யவும். | Get checked at the PHC within two days. |
| GREEN | இன்று இதயத் துடிப்பில் சிக்கல் எதுவும் இல்லை. அடுத்த வருகையில் மீண்டும் பரிசோதிக்கவும். | No rhythm concern today. Recheck at the next visit. |
| RETAKE | சமிக்ஞை தெளிவாக இல்லை. மின்முனைகளைச் சரிசெய்து மீண்டும் பரிசோதிக்கவும். | Signal is not clear. Reposition the electrodes and test again. |

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
