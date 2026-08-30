# ArogyaX — Product Document

**An offline atrial-fibrillation screening layer for Tamil Nadu's *Makkalai Thedi Maruthuvam* doorstep health scheme.**

Team CONGNIVISTA · NIT Trichy · Healthcare

> This is the product/concept document (problem, solution, fit, impact, deployment).
> The engineering build spec lives in this project's internal CLAUDE.md notes.

---

## 1. Executive summary

Tamil Nadu already sends a health worker to the doorstep of every adult aged 45+ to screen for diabetes and hypertension. It does not check heart rhythm. Atrial fibrillation (AF) — a silent, intermittent, easily treatable arrhythmia — is a leading cause of stroke, common in exactly this population, and invisible to a glucometer and a BP cuff.

ArogyaX adds the missing rhythm check. It turns the health worker's existing budget Android phone into an offline AF screening station using a ~₹2,500 single-lead ECG sensor unit. It flags suspected AF for clinician confirmation and routes the patient to the Primary Health Centre (PHC) — where the treatment (anticoagulation) is already stocked. Every AF caught is a stroke that can be prevented.

We are a **screening and triage layer, not a diagnostic device.** A clinician makes every diagnosis.

---

## 2. Problem statement

**Tamil Nadu already visits every elderly doorstep — but it can't see the heart.**

Since 2021, the *Makkalai Thedi Maruthuvam* (MTM, "Medicine at People's Doorstep") scheme has deployed Women Health Volunteers (WHVs) to screen adults aged 45+ and people with disabilities at home — for **blood sugar and blood pressure** — and to deliver medicines. The workforce, the doorstep model, the target population, and the referral chain all exist and function today.

But the screening stops there. Hypertension is a leading driver of cardiac events, yet the same worker, standing in front of the same at-risk patient, has no way to check heart rhythm. Atrial fibrillation is:

- **Silent** — often discovered only after a stroke has already happened.
- **Intermittent** — it comes and goes, so a single clinic ECG frequently misses it.
- **Common and undiagnosed** in rural India — community screening in rural Gujarat found AF prevalence around 5.1% in people aged 50+, far higher than historically reported, because nobody was screening.
- **A major stroke driver** — AF carries roughly a five-fold increased risk of ischemic stroke and precedes or coincides with a large share of ischemic strokes.
- **Cheaply treatable once found** — anticoagulation, already available at the PHC, largely reverses the risk.

Commercial portable ECGs exist (e.g. Spandan ~₹5,500, Agatsa SanketLife ~₹4,250) but they target doctors and clinics, require interpretation the WHV can't provide, and were never designed to slot into a phone-based doorstep workflow.

**The gap is not the workforce, the willingness, or even the hardware. It is a missing sensing layer at the worker's level — and a way to run it offline on the phone she already carries.**

---

## 3. Solution

ArogyaX plugs the rhythm-shaped hole in MTM with three pieces:

1. **A ~₹2,500 sensor unit** — a single-lead (lead II) ECG front-end (AD8232) plus a
   contact PPG sensor (MAX30102), driven by a classic ESP32 (GPIO32/33/34 for the AD8232
   are ADC1-capable only on the original ESP32, not the S3), streaming to the phone over
   Bluetooth. Motion is inferred from these two signals rather than sensed by a dedicated
   accelerometer (§5.3).
2. **The worker's own phone as the compute** — a quantized on-device model reads the rhythm and outputs a referral priority tier, fully offline. Marginal compute cost per additional worker: ₹0.
3. **A guideline-faithful workflow** — the phone camera does a quick pulse (PPG) pre-screen; an irregular pulse triggers the ECG confirmation. This mirrors the internationally endorsed *pulse-check-then-ECG* method for opportunistic AF screening in people 65+.

The result reaches the WHV as a colour-coded tier and a short **Tamil** explanation. Records queue on the device and sync to a PHC dashboard whenever any connectivity appears — no live network is ever needed for the patient to get an answer.

### Why single-lead is the right choice, not a compromise
AF detection is a rhythm problem, and a single good lead reads rhythm reliably. The multi-lead views that a 12-lead ECG provides exist mainly to *localise a heart attack* — a job we deliberately leave to a clinician and out of scope. Our one lead is exactly what the guideline-endorsed AF use case requires.

### The longitudinal advantage
AF is intermittent, so one snapshot misses cases. The clinical answer is a 14-day patch monitor — expensive and impractical at a doorstep. But the WHV **already revisits the same households repeatedly** for BP and glucose. ArogyaX captures a short rhythm strip each visit, turning the scheme's existing revisit cadence into a free, longitudinal sampling strategy that catches intermittent AF a single clinic strip would miss.

---

## 4. How it fits the TN scheme (fit map)

| MTM already has | ArogyaX adds |
|---|---|
| WHVs visiting 45+/disabled at the doorstep | A rhythm check at the same visit, on the same phone |
| Diabetes + hypertension screening | The missing third NCD threat: cardiac rhythm / AF |
| A defined target cohort (45+/65+) | Screening confined to that cohort — no over-screening |
| Repeat visits to the same households | Longitudinal AF capture across those repeat visits |
| A referral chain to the PHC | Auto-prioritised, geo-tagged referrals to the same PHC |
| Medicines delivered at the door | A pathway to a treatment (anticoagulation) already stocked |

**Key point for stakeholders:** this is an *add-on to a working government programme*, not new infrastructure. No new workforce, no new visit, no procurement of a new device class per patient — the phone is already in the worker's hand. That is what makes it deployable rather than a pilot that never scales.

---

## 5. Core features

1. **Offline AF screening** — on-device model, no cloud, works in airplane mode. The dead-zone problem that defeats telemedicine doesn't apply.
2. **Pulse-then-ECG workflow** — phone-camera PPG pre-screen triggers ECG confirmation; mechanises the endorsed clinical algorithm.
3. **Motion-gated capture** — the app rejects any trace recorded while the patient
   moved (movement mimics AF, killing the false positives that discredit screening
   tools), inferring movement from ECG baseline wander and, when available, contact-PPG
   perfusion instability, rather than from a dedicated motion sensor.
4. **Signal-quality gate** — the app refuses to score a poor-contact trace and prompts a retake, so a misplaced electrode never becomes a false referral.
5. **Tamil explanation** — templated, clinician-reviewable text plus voice; never machine-generated medical prose.
6. **Referral priority tier, not a diagnosis** — output is *how urgently to be seen*, ending in "refer to PHC within N hours," never a condition name.
7. **Opportunistic sync + risk map** — records queue locally and upload on any signal; the phone's GPS geo-tags each screening so the PHC sees where cardiac risk clusters.

---

## 6. The technical differentiator (why it's more than an ECG app)

**Shrinking a model to fit a budget phone silently degrades it — and almost nobody measures it.**

To run on a low-end phone, the model is quantized to INT8. This shifts its internal score distribution. The standard workflow picks the decision threshold on the full-precision model and then quantizes — so the *deployed* model runs at an uncalibrated operating point and quietly loses sensitivity. In an AF screen, lost sensitivity means missed AF, which means preventable strokes not prevented.

ArogyaX measures this shift and refits the decision threshold on the quantized model's own scores, restoring sensitivity. The same principle covers two other deployment realities: the sensor's narrower bandwidth versus clinical ECG, and the difference between the training population and Tamil patients.

**Framing:** the decision threshold is not a property of the model alone — it is a property of *model + hardware + population*, all of which change at deployment. Measuring and correcting for that is the contribution, and it holds regardless of whose hardware runs the model.

---

## 7. Impact

- **Clinical:** each AF caught early is an opportunity to prevent a stroke with a treatment already available at the PHC. Strokes are among the most disabling and costly outcomes to a rural household — prevention is dramatically cheaper than the aftermath.
- **System:** fills a documented gap in a live state scheme without adding a visit, a worker, or per-patient device cost. Referral data and the risk map give district health officers a planning tool they don't currently have.
- **Equity:** works offline, in the dead zones where telemedicine fails and where the need is greatest; uses contact sensing (not face-video pulse detection), which avoids the skin-tone and lighting biases that would disadvantage the target population.
- **Scalability:** the compute is a phone the worker already owns, so cost per additional worker is the sensor unit alone.

---

## 8. Cost

| Item | Cost |
|---|---|
| Sensor unit (ESP32 + AD8232 + MAX30102 + battery + enclosure) | **≈ ₹1,840–2,880 per station** |
| Phone (already owned by the worker) | **₹0** |
| Recurring — ECG electrodes | **₹8–15 per patient** |

Compared with connected Edge-AI kiosks (₹50,000+ per unit) that never leave the pilot stage, the marginal cost model is what makes district-wide rollout realistic.

---

## 9. Deployment path

1. **Pilot cluster** — deploy across one district's WHV cohort to reach the visit density where the longitudinal AF-capture advantage and the referral map become meaningful.
2. **Clinician-in-the-loop validation** — every flagged case is confirmed by a PHC clinician; confirmations feed model improvement and build the evidence base.
3. **Regulatory** — scoped as referral-priority decision support (clinician in the loop), which sits in a lighter bracket than a standalone diagnostic; a full diagnostic claim would be a later, separately validated step.
4. **Roadmap** — SMS alert for top-tier cases in low-signal areas; six-lead upgrade (frontal-plane axis) where useful; expansion of the screening panel; district-level risk analytics.

---

## 10. Scope boundaries (stated deliberately)

- **In scope:** atrial fibrillation, heart rhythm, and rate — what a single lead genuinely supports.
- **Out of scope:** myocardial infarction / infarct localisation (needs 12-lead), and any standalone diagnosis. These are clinician responsibilities, and saying so is what keeps the product honest and safe.
- **Every reported performance number** is measured on a patient-disjoint split, labelled with the lead used; anything not measured is labelled a target, not a result.
- **Named competition** (Spandan, Agatsa) is acknowledged openly; ArogyaX differentiates on the worker-level workflow and the deployment-safety layer, not on out-speccing their hardware.

---

## 11. One-line pitch

*"A quarter of strokes come from atrial fibrillation nobody caught, because it's silent and comes and goes. Tamil Nadu already sends a health worker to every elderly doorstep for blood pressure and sugar — but never checks heart rhythm. ArogyaX adds that one missing check, using the guideline-endorsed pulse-then-ECG method, offline, on the phone she already carries, catching intermittent cases across the repeat visits a single clinic ECG would miss. Every AF we find is a stroke we can prevent with a pill already in the PHC."*
