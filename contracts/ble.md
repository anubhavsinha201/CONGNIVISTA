# Contract: BLE wire format

**Status:** LOCKED at H0. Changing anything here requires telling both A (firmware) and B (app) out loud.

Firmware and app are built in parallel against this document. Neither waits for the other.

---

## 1. GATT layout

Device advertises as `ArogyaX-<last 4 of MAC>`.

| Role | UUID |
|---|---|
| Service | `7a9c0100-5d2e-4b81-9f13-2c6e0a4d55e0` |
| ECG stream (notify) | `7a9c0101-5d2e-4b81-9f13-2c6e0a4d55e0` |
| Status (notify) | `7a9c0102-5d2e-4b81-9f13-2c6e0a4d55e0` |
| Control (write) | `7a9c0103-5d2e-4b81-9f13-2c6e0a4d55e0` |

---

## 2. Byte order — read this before writing parser code

**Everything is little-endian.** The ESP32 is little-endian and writes structs directly.
Dart's `ByteData` defaults to **big-endian**, so every read in the app must pass
`Endian.little` explicitly:

```dart
final seq = bytes.getUint16(0, Endian.little);   // correct
final seq = bytes.getUint16(0);                  // WRONG - silently byte-swapped
```

This is the single most likely first-hour integration bug. It presents as a waveform
that looks like noise rather than as an error.

---

## 3. ECG frame — 56 bytes, notified at 10 Hz

Sample rate is **250 Hz**. Each frame carries 25 samples = 100 ms of signal.

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | `uint16` | `seq` | Monotonic frame counter, wraps at 65535 |
| 2 | `uint32` | `t_ms` | Device `millis()` at the **first** sample of this frame |
| 6 | `int16[25]` | `samples` | ADC value minus 2048, so range is ±2048 |

Total: 6 + 50 = **56 bytes**. This exceeds the default ATT MTU — see §6.

### Sample units
Samples are **arbitrary units (ADU)**, not millivolts. We deliberately do not calibrate
to mV: AF detection is a **timing** problem (RR interval irregularity), not an amplitude
problem, and claiming a mV scale we have not calibrated against a reference would be
dishonest. The app displays the trace autoscaled.

### Dropped-frame handling — safety critical
`seq` exists so the app can detect gaps. **A dropped frame must invalidate the window.**

If frames 41 and 43 arrive but 42 does not, naive concatenation deletes 100 ms of signal
and manufactures a short RR interval out of nothing. That looks exactly like atrial
fibrillation. A BLE glitch must never become a referral.

App rule: any `seq` gap inside an analysis window sets `motionRejected`-style
invalidation and the result is **RETAKE**, never a tier.

---

## 4. Status frame — 4 bytes, notified at 1 Hz

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | `uint8` | `flags` | bit0 `LO+` off, bit1 `LO-` off, bit2 reserved, bit3 streaming |
| 1 | `uint8` | `batteryPct` | 0-100, 255 = unknown |
| 2 | `uint16` | `lastEcgSeq` | `seq` of the most recent ECG frame sent |

### Lead-off comes from hardware
Bits 0 and 1 are read from the AD8232 `LO+` / `LO-` pins. Do **not** infer electrode
detachment in software from a flat trace - the hardware already knows, and it knows faster.

### Motion is inferred, not sensed
There is no IMU in this build. The MPU-6050 that used to fill flag bit2 (`IMU ready`) and
`accelVarMilliG` is not in the BOM; that bit is reserved rather than reused, so a firmware
built against an earlier revision of this contract fails visibly instead of silently
setting a bit the app no longer reads.

Motion is inferred on the phone instead, from signals already present in the streams this
device sends anyway: ECG baseline wander (`sqi.dart`) and, when a simultaneous contact-PPG
capture exists, its perfusion-index instability (`contracts/ppg.md` section 5). Nothing
new is added to the wire format for this - the ECG and PPG frames already carry everything
the inference needs. See `Policy.kMotionWanderRatioGate` and
`Policy.kMotionPerfusionInstabilityGate` in `contracts/tiers.md` section 4 for the
thresholds, both marked PROVISIONAL until tuned against real disturbed-vs-still captures.

---

## 5. Control characteristic — 2 bytes, write

| Byte 0 (opcode) | Byte 1 (arg) | Meaning |
|---|---|---|
| `0x01` | `0x00` | Start ECG stream |
| `0x02` | `0x00` | Stop ECG stream |
| `0x03` | seconds | Start a fixed-duration capture, then auto-stop |

Firmware boots **not streaming**. The app explicitly starts it. This keeps the battery
alive across a day of visits.

---

## 6. Connection parameters

56-byte notifications exceed the 23-byte default ATT MTU, so the app **must** request
an MTU of at least 64 (`requestMtu(185)` on Android) after connecting and before
subscribing. Firmware accepts whatever the central negotiates.

Throughput sanity check: 250 Hz x 2 bytes = 500 B/s payload. This is nothing for BLE.
If it is unstable, the cause is MTU or connection interval, never bandwidth.

Requested connection interval: 20-40 ms.

---

## 7. Fallback

If BLE proves unstable at the venue, the identical frame format is emitted over USB
serial at 921600 baud, framed with a `0xAA 0x55` preamble. The app's `SignalSource`
abstraction means only the transport changes, not the parser or anything downstream.
