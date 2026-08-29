# Contract: contact PPG (MAX30102) and ECG/PPG fusion

**Status:** LOCKED. Extends `contracts/ble.md`.

Terminology: this is **contact reflectance PPG**, not rPPG. rPPG is remote/camera
photoplethysmography, which docs/PRODUCT.md §7 deliberately rejects because face-video
pulse extraction carries skin-tone and lighting bias that would fall hardest on exactly
this population. A finger sensor has no such failure mode. Do not call it rPPG.

---

## 1. Why a second sensor at all

A PPG measures the same cardiac rhythm the ECG does, so PPG-irregularity and
RR-irregularity are two views of one signal and are strongly correlated. Adding PPG
purely as "a second opinion on rhythm" buys very little and invites the question of why
a screening tool needs two sensors to answer one question.

The justification is **pulse deficit**, and it is not available from either sensor alone.

In atrial fibrillation, ventricular contractions arrive irregularly. A beat that follows
too soon gives the ventricle insufficient filling time, so its stroke volume is too small
to generate a peripheral pulse. The heart contracts; the finger feels nothing. The
electrical rate therefore **exceeds** the mechanical rate, and the difference — the pulse
deficit — is a long-standing bedside sign of AF.

Measuring it requires simultaneous electrical and mechanical sensing on a shared clock.
That is a genuinely multimodal quantity, and it is the reason this sensor is here.

Secondary benefits, in honest order of value:

1. **Electrode economy.** Electrodes cost ₹8–15 per patient and are the only recurring
   cost in the model (PRODUCT.md §8). PPG is reusable and costs nothing per patient. A
   PPG pre-screen that only escalates to ECG when the pulse looks irregular follows the
   guideline-endorsed *pulse-check-then-ECG* algorithm (PRODUCT.md §3) **and** cuts
   electrode consumption substantially.
2. **Perfusion confirmation.** A detected R peak with no corresponding pulse is either a
   non-perfusing beat or a false R-peak detection. Either way the app learns something
   it could not otherwise know.
3. **Independent quality evidence.** Perfusion index gives a contact-quality measure that
   does not depend on the ECG electrodes at all.
4. **Motion inference.** With the MPU-6050 no longer in the BOM, nothing on the sensor
   unit senses motion directly. Perfusion-index instability across a capture — the signal
   swinging up and down as a finger shifts on the sensor — is patient-side evidence of
   movement, and it corroborates the ECG's own baseline-wander signal (`sqi.dart`) in the
   inferred-motion gate. See section 5.

**Out of scope:** SpO2. The MAX30102's red channel supports it, but oxygen saturation is
a different clinical question with its own validation burden. Roadmap, not this build.

---

## 2. Hardware

MAX30102 at I²C address `0x57`. It is the **only** I²C device in this build — the
MPU-6050 that an earlier revision of this contract paired it with is not in the BOM. See
`contracts/ble.md` section 4 for what that removal changes about the status frame, and
section 5 below for how motion detection is replaced rather than dropped.

Configuration:

| Setting | Value | Why |
|---|---|---|
| Mode | Heart-rate (IR only) | SpO2 is out of scope; IR alone halves the BLE payload |
| Sample rate | **100 Hz** | Ample for beats up to 240 bpm; 4× the highest frequency of interest |
| Pulse width | 411 µs (18-bit) | Best resolution; perfusion index needs the dynamic range |
| LED current | ~6–12 mA, adjustable | Raise it if perfusion index is low — cold fingers are the common case |
| Sensor placement | Index fingertip, light steady pressure | Pressing hard occludes capillaries and flattens the waveform |

---

## 3. BLE — PPG characteristic

Added to the service in `contracts/ble.md` §1:

| Role | UUID |
|---|---|
| PPG stream (notify) | `7a9c0104-5d2e-4b81-9f13-2c6e0a4d55e0` |

### PPG frame — 46 bytes, notified at 10 Hz

100 Hz sample rate, 10 samples per frame. Little-endian, as everything else.

| Offset | Type | Field |
|---|---|---|
| 0 | `uint16` | `seq` |
| 2 | `uint32` | `t_ms` — device `millis()` at the first sample |
| 6 | `int32[10]` | IR counts (18-bit values, sign-extended) |

Sequence gaps invalidate the window exactly as they do for ECG
(`contracts/ble.md` §3), and for the same reason: a gap fabricates a missing pulse,
which is precisely the pulse-deficit signature. A dropped frame must never look like AF.

---

## 4. THE SHARED CLOCK — the thing that makes any of this work

**ECG `t_ms` and PPG `t_ms` MUST come from the same `millis()` on the same ESP32.**

Pulse deficit and beat-to-beat coupling are measured by comparing the timing of
electrical events against mechanical ones. If the two streams carry independent or
drifting timebases, every derived quantity in §6 is noise wearing the costume of a
clinical finding.

Concretely: the firmware must timestamp both frame types from one monotonic clock, and
must not reset or adjust that clock during a capture. The phone's arrival time must
**never** be used — BLE buffering jitter is tens of milliseconds, which is the same order
as the pulse transit time being measured.

---

## 5. PPG signal processing

Mirrors the ECG path so the two are comparable:

```
IR counts (100 Hz)
  -> perfusion index computed on the RAW signal (needs DC)
  -> bandpass 0.5-5 Hz, zero-phase          FilterChain.ppgBand
  -> systolic peak detection, 250 ms refractory
  -> inter-beat intervals (IBI, ms)
  -> RMSSD / pNN50 / Shannon entropy        same statistics as RR
```

**Perfusion index = AC / DC × 100**, where AC is the peak-to-peak of the pulsatile
component and DC is the mean raw level. Below ~0.3% the trace is not trustworthy —
usually a cold finger, poor contact, or too little LED current. This is the PPG's
signal-quality gate and is the exact analogue of `sqi.dart` for the ECG.

### Perfusion stability — the PPG's motion signal

A **separate** measure from perfusion index itself: how much the perfusion index varies
across 1 s sub-windows of the capture, normalised by the capture's overall DC level. Zero
for a steady contact regardless of whether that contact is good or bad — a uniformly weak
signal is the perfusion-index gate's job, not this one's — and high only when signal
quality swings within a single capture, which is what a shifting finger produces.

Computed by filtering the **whole** capture once, then measuring local amplitude spread on
the already-filtered signal. Re-running the bandpass from scratch on each short sub-window
independently was tried first and rejected: a 0.5 Hz highpass needs on the order of
seconds to settle, and filtering an isolated ~1 s slice produces edge-transient noise of
the same order as the physiological signal being measured — large enough to make a
perfectly steady synthetic capture read as unstable during validation
(`ml/reference/validate_ppg.py`). Filter once, window the result.

---

## 6. Fusion features

Computed only when ECG and PPG were captured **simultaneously** on the shared clock.

### `pulseDeficitBpm` = HR_ecg × (1 − perfusedBeatFraction)

The rate at which beats fail to reach the periphery. Around 10 bpm and above is
clinically meaningful; a healthy regular rhythm sits at zero.

**It is derived from `perfusedBeatFraction`, not measured independently of it.** These
are one finding expressed two ways — the fraction per beat, the deficit per minute, which
is how the sign is read at the bedside (apex rate minus radial rate). The policy may
treat them as a single piece of evidence, which is what they are. **Do not present them
to a judge or a clinician as two corroborating measurements.**

The obvious alternative — count R peaks and pulses in a shared time window and subtract
the rates — is wrong, and wrong in a way that returns a plausible number instead of an
error. The two event types are separated by the transit time, so any window shared
between them contains one more pulse than it does beats. On a healthy volunteer that
yields a *negative* deficit, which is physiologically impossible, and the impossibility
check then fires a RETAKE on a perfectly good capture.

Work on the ECG timeline instead, bounded to beats whose pulse could have been observed:
`start = max(ecg₀, ppg₀ − PTT_max)`, `end = min(ecg_last, ppg_last − PTT_min)`.

### Detector-failure check

A finger cannot pulse without a heartbeat. **More pulses than R peaks** in the window
means the R-peak detector missed beats — which fabricates long RR intervals that would
otherwise be scored as an irregular rhythm. Return RETAKE.

This is a real benefit of the second sensor: the PPG catches an ECG analysis failure that
the ECG cannot detect on its own.

### `perfusedBeatFraction`

For each detected R peak, look for a PPG **systolic peak** within **150–450 ms**. The
fraction of R peaks with a matching pulse.

The window is R-peak-to-systolic-peak, not R-peak-to-pulse-foot. The foot arrives
~150–300 ms after the R peak; the systolic peak a further ~140 ms after that. Using a
foot-derived window against peak detections silently reports perfusing beats as
non-perfusing — which surfaces as a clinical finding rather than as an error.

- ≈ 1.0 — every beat perfuses. Normal.
- Materially below 1.0 with an irregular rhythm — non-perfusing beats, the mechanical
  signature of AF.
- Low with a *regular* rhythm — suspect the PPG contact, not the patient. Gate, do not score.

This is finer-grained than the rate difference: it says *which* beats failed to perfuse,
not merely how many.

---

## 7. How PPG enters the decision

PPG **raises confidence and urgency. It never rescues a bad ECG and never overrides a gate.**

| Situation | Effect |
|---|---|
| PPG pre-screen irregular or unclear | Escalate to ECG capture (the guideline algorithm) |
| PPG pre-screen regular | ECG still offered; PPG alone never clears a patient |
| Pulse deficit ≥ 10 bpm **and** rhythm irregular | Corroborating evidence — supports RED |
| Perfused-beat fraction low **and** rhythm irregular | Corroborating evidence — supports RED |
| Perfusion index below gate | PPG features are dropped; the ECG decision stands alone |
| Pulse deficit negative | RETAKE — the R-peak detector is wrong |

**PPG can never turn a RED into a GREEN.** A screening instrument biased toward
sensitivity must not acquire a new way to reassure. Every PPG path either escalates,
corroborates, or is discarded.

Thresholds live in `Policy`, alongside every other constant.

---

## 8. Honesty constraints

- The pulse-deficit thresholds below are **physiologically motivated, not fitted** —
  there is no paired ECG+PPG AF dataset in this build to fit them on. Label them as
  targets, per PRODUCT.md §10, until measured.
- **The CNN stays ECG-only.** No paired ECG+PPG AF-labelled training data is available
  here, and a multimodal model trained on mismatched sources would be weaker than the
  ECG model it replaced while sounding more impressive. The fusion in §6 is explicit,
  inspectable arithmetic — which is also what lets a clinician check it.
