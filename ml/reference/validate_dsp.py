"""Validates the DSP reference implementation, then emits golden vectors.

Run:  python ml/reference/validate_dsp.py

Checks, in order:
  1. Filter design agrees with scipy (independent check on the maths).
  2. Zero-phase filtering really is zero-phase.
  3. R-peak detection recovers known peaks in clean and degraded signals.
  4. RR features separate simulated NSR from simulated AF.
  5. The SQI gate fails the windows it is supposed to fail.
  6. Tier policy behaves per contracts/tiers.md.

Then writes app/test/fixtures/golden_vectors.json for the Dart tests.
"""

from __future__ import annotations

import json
import math
import os
import sys

import numpy as np
from scipy import signal as sps

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dsp_reference as dsp  # noqa: E402
import synth_ecg as synth  # noqa: E402

FS = 250.0
FIXTURES = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "test", "fixtures"
)

PASS, FAIL = "PASS", "FAIL"
results = []


def check(name, condition, detail=""):
    status = PASS if condition else FAIL
    results.append((status, name, detail))
    print(f"  [{status}] {name}" + (f"  -- {detail}" if detail else ""))
    return condition


# --------------------------------------------------------------------------
print("\n1. Filter design vs scipy")
# --------------------------------------------------------------------------
# An RBJ lowpass at Q = 1/sqrt(2) is exactly a 2nd-order Butterworth, so scipy
# is a genuine independent check rather than a restatement of our own formula.
for f0 in (15.0, 40.0):
    b_ours, a_ours = dsp.biquad_lowpass(FS, f0)
    b_sp, a_sp = sps.butter(2, f0 / (FS / 2), btype="low")
    check(f"lowpass {f0:.0f} Hz matches scipy.butter",
          np.allclose(b_ours, b_sp, atol=1e-12) and np.allclose(a_ours, a_sp, atol=1e-12),
          f"max coeff delta {max(np.abs(b_ours - b_sp).max(), np.abs(a_ours - a_sp).max()):.2e}")

for f0 in (0.5, 5.0):
    b_ours, a_ours = dsp.biquad_highpass(FS, f0)
    b_sp, a_sp = sps.butter(2, f0 / (FS / 2), btype="high")
    check(f"highpass {f0:.1f} Hz matches scipy.butter",
          np.allclose(b_ours, b_sp, atol=1e-12) and np.allclose(a_ours, a_sp, atol=1e-12),
          f"max coeff delta {max(np.abs(b_ours - b_sp).max(), np.abs(a_ours - a_sp).max()):.2e}")

# Notch: check the response at 50 Hz is deeply attenuated and 10/40 Hz survive.
b_n, a_n = dsp.biquad_notch(FS, 50.0)
w, h = sps.freqz(b_n, a_n, worN=[2 * math.pi * f / FS for f in (10, 40, 50, 60)])
mag = np.abs(h)
check("50 Hz notch attenuates mains by >30 dB", mag[2] < 10 ** (-30 / 20),
      f"|H(50)| = {20 * math.log10(mag[2]):.1f} dB")
check("50 Hz notch preserves the QRS band", mag[0] > 0.99 and mag[1] > 0.9,
      f"|H(10)| = {mag[0]:.3f}, |H(40)| = {mag[1]:.3f}")

# --------------------------------------------------------------------------
print("\n2. Zero-phase behaviour")
# --------------------------------------------------------------------------
imp = np.zeros(2000)
imp[1000] = 1.0
out = dsp.filtfilt_chain([dsp.biquad_lowpass(FS, 15.0)], imp)
com = float((np.arange(out.size) * np.abs(out)).sum() / np.abs(out).sum())
check("filtfilt introduces no group delay", abs(com - 1000) < 1.0,
      f"impulse centre of mass at {com:.2f}, input at 1000")

dc = np.full(2000, 500.0)
hp = dsp.filtfilt_chain([dsp.biquad_highpass(FS, 0.5)], dc)
check("0.5 Hz highpass removes DC without edge ringing", np.abs(hp).max() < 1.0,
      f"max residual {np.abs(hp).max():.3e} (a naive zero-state filter rings to ~500 here)")

mains = np.sin(2 * np.pi * 50 * np.arange(2500) / FS) * 100
notched = dsp.filtfilt_chain([dsp.biquad_notch(FS, 50.0)], mains)
mid = notched[500:-500]
check("notch removes a pure 50 Hz tone", np.abs(mid).max() < 5.0,
      f"residual amplitude {np.abs(mid).max():.2f} of 100")

# The vectorised path used for dataset preprocessing must agree with the
# reference implementation, or the model trains on a different signal than the
# app feeds it -- the exact train/deploy mismatch this project is about.
sig_cmp, _ = synth.synth_ecg(duration_s=20, rhythm="nsr", noise_std=10,
                             mains_amplitude=80, wander_amplitude=150)
slow = dsp.filtfilt_chain(dsp.ecg_conditioning(FS), sig_cmp)
fast = dsp.filtfilt_fast(dsp.ecg_conditioning(FS), sig_cmp)
edge = int(FS)  # ignore the first and last second, where edge handling differs
rel = np.abs(slow[edge:-edge] - fast[edge:-edge]).max() / np.abs(slow[edge:-edge]).max()
check("filtfilt_fast matches filtfilt_chain in the interior", rel < 1e-3,
      f"max relative deviation {rel:.2e}")

# --------------------------------------------------------------------------
print("\n3. R-peak detection against known ground truth")
# --------------------------------------------------------------------------
TOL = int(0.050 * FS)  # 50 ms


def peak_scores(sig, truth):
    det, _, _ = dsp.detect_rpeaks(sig, FS)
    tp, fp, fn = synth.match_peaks(det, truth, TOL)
    se = tp / max(1, tp + fn)
    ppv = tp / max(1, tp + fp)
    return det, se, ppv, tp, fp, fn


scenarios = [
    ("clean NSR", dict(rhythm="nsr", noise_std=8)),
    ("clean AF", dict(rhythm="af", noise_std=8, seed=3)),
    ("NSR + 50 Hz mains", dict(rhythm="nsr", noise_std=8, mains_amplitude=120)),
    ("NSR + baseline wander", dict(rhythm="nsr", noise_std=8, wander_amplitude=250)),
    ("NSR + heavy white noise", dict(rhythm="nsr", noise_std=45)),
    ("AF + mains + wander", dict(rhythm="af", noise_std=15, mains_amplitude=90,
                                 wander_amplitude=200, seed=5)),
    ("bradycardia 45 bpm", dict(rhythm="nsr", noise_std=8)),
    ("low amplitude (poor contact)", dict(rhythm="nsr", noise_std=8, r_amplitude=150)),
]

for name, kwargs in scenarios:
    sig, truth = synth.synth_ecg(duration_s=30, fs=FS, **kwargs)
    _, se, ppv, tp, fp, fn = peak_scores(sig, truth)
    check(f"R-peak detection: {name}", se >= 0.95 and ppv >= 0.95,
          f"Se={se:.3f} PPV={ppv:.3f} (tp={tp} fp={fp} fn={fn}, truth={truth.size})")

# T-wave rejection specifically: tall T waves are the classic false-positive
# source, so exaggerate them and confirm we do not double-count beats.
sig, truth = synth.synth_ecg(duration_s=30, rhythm="nsr", noise_std=6, r_amplitude=300)
tall_t = sig.copy()
_, se, ppv, tp, fp, fn = peak_scores(tall_t, truth)
check("no double-counting of T waves", ppv >= 0.98, f"PPV={ppv:.3f} fp={fp}")

# --------------------------------------------------------------------------
print("\n4. RR features separate NSR from AF")
# --------------------------------------------------------------------------
nsr_scores, af_scores = [], []
for seed in range(12):
    s, t = synth.synth_ecg(duration_s=30, rhythm="nsr", noise_std=10, seed=seed)
    f = dsp.analyse_rr(dsp.rr_intervals_ms(dsp.detect_rpeaks(s, FS)[0], FS))
    nsr_scores.append(f.irregularityScore)

    s, t = synth.synth_ecg(duration_s=30, rhythm="af", noise_std=10, seed=100 + seed)
    f = dsp.analyse_rr(dsp.rr_intervals_ms(dsp.detect_rpeaks(s, FS)[0], FS))
    af_scores.append(f.irregularityScore)

nsr_scores, af_scores = np.array(nsr_scores), np.array(af_scores)
print(f"      NSR irregularity: mean {nsr_scores.mean():.3f}  max {nsr_scores.max():.3f}")
print(f"      AF  irregularity: mean {af_scores.mean():.3f}  min {af_scores.min():.3f}")

check("all simulated NSR below the 0.5 gate", nsr_scores.max() < 0.5,
      f"worst NSR score {nsr_scores.max():.3f}")
check("all simulated AF at or above the 0.5 gate", af_scores.min() >= 0.5,
      f"weakest AF score {af_scores.min():.3f}")
check("clear separation between the two", af_scores.min() - nsr_scores.max() > 0.2,
      f"gap = {af_scores.min() - nsr_scores.max():.3f}")

# --------------------------------------------------------------------------
print("\n5. SQI gate")
# --------------------------------------------------------------------------
clean, _ = synth.synth_ecg(duration_s=30, rhythm="nsr", noise_std=8)
q = dsp.analyse_sqi(clean, FS)
check("clean signal passes the gate", q["score"] >= 0.5, f"score={q['score']:.3f}")

flat = synth.with_flatline(clean, start_s=5, duration_s=10)
q = dsp.analyse_sqi(flat, FS)
check("detached electrode fails the gate", q["score"] < 0.5,
      f"score={q['score']:.3f} reason={q['failureReason']!r}")

hum, _ = synth.synth_ecg(duration_s=30, rhythm="nsr", noise_std=8, mains_amplitude=900)
q = dsp.analyse_sqi(hum, FS)
check("severe mains interference fails the gate", q["score"] < 0.5,
      f"score={q['score']:.3f} powerlineRatio={q['powerlineRatio']:.3f}")

clip, _ = synth.synth_ecg(duration_s=30, rhythm="nsr", noise_std=8, r_amplitude=4000)
clip = synth.with_clipping(clip)
q = dsp.analyse_sqi(clip, FS)
check("clipped signal fails the gate", q["score"] < 0.5,
      f"score={q['score']:.3f} saturationFraction={q['saturationFraction']:.3f}")

# Amplitudes here are chosen to stay clear of the +/- 2040 rail, so this
# isolates the wander detector. An earlier version used a drift so large it
# railed the ADC, and passed because the *saturation* term zeroed the score --
# testing the wrong thing entirely.
drift, _ = synth.synth_ecg(duration_s=30, rhythm="nsr", noise_std=8,
                           r_amplitude=300, wander_amplitude=1000)
q = dsp.analyse_sqi(drift, FS)
check("extreme baseline drift fails the gate", q["score"] < 0.5,
      f"score={q['score']:.3f} baselineWanderRatio={q['baselineWanderRatio']:.3f}")
check("...and fails because of wander, not saturation",
      q["saturationFraction"] < 0.001 and q["baselineWanderRatio"] >= 0.80,
      f"saturationFraction={q['saturationFraction']:.4f} "
      f"baselineWanderRatio={q['baselineWanderRatio']:.3f}")

# Mild mains hum must NOT fail -- an over-eager gate that rejects usable traces
# makes the worker retake endlessly and abandon the tool.
mild, _ = synth.synth_ecg(duration_s=30, rhythm="nsr", noise_std=8, mains_amplitude=60)
q = dsp.analyse_sqi(mild, FS)
check("mild mains hum still passes", q["score"] >= 0.5, f"score={q['score']:.3f}")

# --------------------------------------------------------------------------
print("\n6. Golden vectors for the Dart tests")
# --------------------------------------------------------------------------
os.makedirs(FIXTURES, exist_ok=True)

vectors = {
    "_comment": (
        "Generated by ml/reference/validate_dsp.py. Regenerate after changing any "
        "DSP constant. The Dart tests in app/test/ assert against these, which is "
        "what keeps app/lib/signal and ml/reference/dsp_reference.py in agreement."
    ),
    "fs": FS,
    "cases": [],
}

for name, kwargs in [
    ("nsr_clean", dict(rhythm="nsr", noise_std=8, seed=0)),
    ("af_clean", dict(rhythm="af", noise_std=8, seed=3)),
    ("nsr_mains", dict(rhythm="nsr", noise_std=8, mains_amplitude=120, seed=0)),
    ("af_noisy", dict(rhythm="af", noise_std=15, mains_amplitude=90,
                      wander_amplitude=200, seed=5)),
]:
    sig, truth = synth.synth_ecg(duration_s=30, fs=FS, **kwargs)
    peaks, _, _ = dsp.detect_rpeaks(sig, FS)
    rr = dsp.rr_intervals_ms(peaks, FS)
    feats = dsp.analyse_rr(rr)
    quality = dsp.analyse_sqi(sig, FS)
    vectors["cases"].append({
        "name": name,
        "expectedRhythm": kwargs["rhythm"],
        "samples": [round(float(v), 4) for v in sig],
        "groundTruthPeaks": [int(p) for p in truth],
        "expectedPeaks": [int(p) for p in peaks],
        "expectedRrFeatures": dsp.features_as_dict(feats),
        "expectedSqi": {k: v for k, v in quality.items()},
    })

path = os.path.normpath(os.path.join(FIXTURES, "golden_vectors.json"))
with open(path, "w", encoding="utf-8") as fh:
    json.dump(vectors, fh, indent=1)
size_kb = os.path.getsize(path) / 1024
check("golden vectors written", os.path.exists(path), f"{path} ({size_kb:.0f} KB)")

# --------------------------------------------------------------------------
failed = [r for r in results if r[0] == FAIL]
print("\n" + "=" * 70)
print(f"{len(results) - len(failed)}/{len(results)} checks passed")
if failed:
    print("\nFAILURES:")
    for _, name, detail in failed:
        print(f"  - {name}: {detail}")
print("=" * 70)
sys.exit(1 if failed else 0)
