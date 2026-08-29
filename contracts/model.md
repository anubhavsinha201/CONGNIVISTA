# Contract: CNN input/output

**Status:** LOCKED. Track D produces the model to this spec; tracks B/C consume it.

A mismatch here does not throw. It silently degrades accuracy, and the app keeps
returning confident tiers built on a signal the model never learned. Every step below
must be identical on both sides.

---

## 1. The pipeline, in order

```
raw ADU (int16, ADC - 2048, 250 Hz)          <- contracts/ble.md
  -> FilterChain.ecgConditioning(250)        0.5-40 Hz + 50 Hz notch, zero-phase
  -> take exactly 7500 samples (30 s)
  -> per-window z-score
  -> float32 tensor [1, 7500, 1]
  -> quantise to int8 using the model's own scale/zero-point
  -> INT8 CNN
  -> dequantise output -> probability in [0, 1]
  -> compare against Policy.kCnnThresholdInt8
```

Training data goes through **the same first three steps** in `ml/prepare_cinc2017.py`.
That is not a coincidence to be maintained by discipline — it is the point. Training on
one signal and inferring on another is the failure this product exists to name.

---

## 2. Input specification

| Property | Value |
|---|---|
| Sample rate | 250 Hz (training data resampled 300 -> 250) |
| Window | 7500 samples = 30.0 s exactly |
| Conditioning | 0.5 Hz highpass, 40 Hz lowpass, 50 Hz notch, zero-phase |
| Normalisation | per-window z-score |
| Tensor shape | `[1, 7500, 1]`, float32 before quantisation |
| Channels | 1 (lead II) |

### Normalisation — exact definition

```dart
// Must match normalise() in ml/prepare_cinc2017.py exactly.
Float32List zscore(Float64List w) {
  var mean = 0.0;
  for (final v in w) mean += v;
  mean /= w.length;
  var acc = 0.0;
  for (final v in w) { final d = v - mean; acc += d * d; }
  final sd = math.sqrt(acc / w.length);        // population std, NOT sample std
  final out = Float32List(w.length);
  if (sd < 1e-8) return out;                   // all zeros, matching the Python guard
  for (var i = 0; i < w.length; i++) out[i] = (w[i] - mean) / sd;
  return out;
}
```

Two things that are easy to get wrong and will not raise an error:

- **Population standard deviation** (divide by `n`), which is what `numpy.std()` returns
  by default. Dividing by `n - 1` gives a slightly different scale on every window.
- **The `sd < 1e-8` guard returns all zeros**, not the unmodified signal. A flat window
  should never reach the model anyway — the SQI gate catches it — but the two sides must
  agree on what happens if one does.

Amplitude is normalised away deliberately. The AD8232's gain is uncalibrated and samples
are arbitrary units, so absolute amplitude carries no information the model should use.

---

## 3. Quantisation — the app must do this explicitly

The model is **full-integer** quantised: int8 weights, activations, input and output.
`tflite_flutter` does not convert for you when the input tensor is int8. Read the
tensor's quantisation parameters and apply them:

```
q = round(x / scale) + zeroPoint      clamped to [-128, 127]
```

and inverse them on the output:

```
p = (q_out - zeroPointOut) * scaleOut
```

`scale` and `zeroPoint` come from the interpreter's tensor metadata, not from constants
in the app. If they are hardcoded and track D reconverts the model, the app breaks
silently.

Full-integer rather than dynamic-range for two reasons: it is the honest deployment
target on a budget phone, and it is the variant whose score shift the calibration
experiment measures.

---

## 4. Output

Single sigmoid probability that the window is atrial fibrillation.

- **1 = AF**, 0 = not AF (Normal or Other rhythm).
- Compared against `Policy.kCnnThresholdInt8`, which is **refitted on INT8 scores** —
  never inherited from the FP32 model.
- While that threshold is `null`, the app must ignore the CNN entirely rather than
  substitute a guess. An uncalibrated operating point is precisely the failure
  docs/PRODUCT.md section 6 is about; shipping one in our own app would be indefensible.

The probability is never shown to the health worker. It is an input to the tier, and the
tier is what the worker sees. See `contracts/tiers.md` section 1.

---

## 5. Training set summary — for the pitch, stated accurately

| Property | Value |
|---|---|
| Source | PhysioNet/CinC Challenge 2017 training set |
| Positive | `A` — atrial fibrillation |
| Negative | `N` + `O` — normal **and other rhythm** |
| Excluded | `~` — noisy, because the SQI gate routes these to RETAKE |
| Split | **record-disjoint**, stratified by label |

**Say "record-disjoint", not "patient-disjoint".** PhysioNet does not publish subject
identifiers for CinC 2017, so record level is the strongest split obtainable. All windows
cut from one record stay in the same split. docs/PRODUCT.md section 10 commits to
labelling what is measured versus what is assumed; this is one of those places.

Keeping `O` in the negative class is deliberate and is worth saying out loud: it forces
the CNN to separate AF from other irregular rhythms, which is the one clinically useful
thing it can do that `rr_features.dart` structurally cannot.
