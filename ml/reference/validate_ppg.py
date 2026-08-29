"""Validates the contact-PPG path and the ECG/PPG fusion.

Run:  python ml/reference/validate_ppg.py

Checks:
  1. PPG peak detection recovers known pulse times.
  2. Perfusion index behaves, and the gate fires on a weak signal.
  3. Pulse deficit is ~0 in sinus rhythm and clearly positive in AF.
  4. Perfused-beat fraction identifies non-perfusing beats.
  5. Fusion refuses to answer when it should.
  6. The policy escalates on corroboration -- and never reassures.
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dsp_reference as dsp  # noqa: E402
import ppg_reference as ppg  # noqa: E402
import synth_ecg as synth  # noqa: E402
import validate_policy as pol  # noqa: E402

ECG_FS = 250.0
PPG_FS = ppg.PPG_FS

results = []


def check(name, cond, detail=""):
    status = "PASS" if cond else "FAIL"
    results.append((status, name, detail))
    print(f"  [{status}] {name}" + (f"  -- {detail}" if detail else ""))
    return cond


def make_case(rhythm, seed, duration=30.0, fill_ms=None, **ppg_kw):
    """Build a coupled ECG+PPG pair sharing one timebase."""
    ecg, truth = synth.synth_ecg(duration_s=duration, rhythm=rhythm,
                                 noise_std=8, seed=seed)
    r_ms = ppg.r_peaks_to_ms(truth, ECG_FS)
    sig, pulse_ms = ppg.synth_ppg(r_ms, duration, PPG_FS,
                                  perfusion_fill_ms=fill_ms, seed=seed, **ppg_kw)
    return ecg, truth, r_ms, sig, pulse_ms


# --------------------------------------------------------------------------
print("\n1. PPG systolic peak detection")
# --------------------------------------------------------------------------
for rhythm, seed in (("nsr", 0), ("af", 3)):
    _, _, r_ms, sig, pulse_ms = make_case(rhythm, seed)
    res = ppg.analyse_ppg(sig, PPG_FS)
    det_ms = res.peaks * 1000.0 / PPG_FS
    tp, fp, fn = synth.match_peaks(det_ms, pulse_ms, tolerance_samples=120)  # 120 ms
    se = tp / max(1, tp + fn)
    ppv = tp / max(1, tp + fp)
    check(f"{rhythm}: pulses recovered", se >= 0.95 and ppv >= 0.95,
          f"Se={se:.3f} PPV={ppv:.3f} (truth={len(pulse_ms)})")

# --------------------------------------------------------------------------
print("\n2. Perfusion index and its gate")
# --------------------------------------------------------------------------
_, _, r_ms, sig, _ = make_case("nsr", 0)
res = ppg.analyse_ppg(sig, PPG_FS)
check("healthy finger passes the gate", res.usable and res.perfusion_index >= ppg.PERFUSION_GATE,
      f"PI={res.perfusion_index:.2f}%")

# A cold finger: same pulse, far less pulsatile amplitude.
_, _, _, weak, _ = make_case("nsr", 0, ac_fraction=0.0008)
res_weak = ppg.analyse_ppg(weak, PPG_FS)
check("cold finger fails the gate", not res_weak.usable,
      f"PI={res_weak.perfusion_index:.3f}%  reason={res_weak.failure_reason!r}")

check("perfusion index tracks pulsatile amplitude",
      res.perfusion_index > res_weak.perfusion_index * 5,
      f"{res.perfusion_index:.2f}% vs {res_weak.perfusion_index:.3f}%")

# --------------------------------------------------------------------------
print("\n2b. Inferred motion (the MPU-6050 is no longer in the BOM)")
# --------------------------------------------------------------------------
# A dedicated, densely and fully covering pulse train -- NOT the shared r_ms
# above. That one comes from synth_ecg, which deliberately leaves a 1-2 s dead
# zone at each end of its window (so a capture never starts or ends mid-
# complex). Splitting a signal built on it into two halves compounds those
# dead zones into exactly the kind of spurious low-amplitude tail this test
# needs to NOT have, or it would measure the generator's own padding instead
# of the disturbance being injected.
def full_span_r_ms(duration_s, interval_ms=800.0):
    return np.arange(0, duration_s * 1000.0, interval_ms)

# A steady contact: same pulsatile amplitude throughout the capture.
steady, _ = ppg.synth_ppg(full_span_r_ms(30.0), 30.0, PPG_FS, ac_fraction=0.02, seed=10)
stability_steady = ppg.perfusion_stability(steady, PPG_FS)
check("steady contact has low perfusion stability ratio", stability_steady < 0.3,
      f"ratio={stability_steady:.3f}")

# A finger that shifts partway through: amplitude drops sharply in the second
# half. Built by concatenating two independently-generated, fully-covering
# segments rather than varying synth_ppg's amplitude over time, since only the
# stability metric is under test here.
half = 15.0
first, _ = ppg.synth_ppg(full_span_r_ms(half), half, PPG_FS, ac_fraction=0.02, seed=11)
second, _ = ppg.synth_ppg(full_span_r_ms(half), half, PPG_FS, ac_fraction=0.004, seed=12)
disturbed = np.concatenate([first, second])
stability_disturbed = ppg.perfusion_stability(disturbed, PPG_FS)
check("a mid-capture amplitude shift raises the stability ratio",
      stability_disturbed >= ppg.MOTION_PERFUSION_INSTABILITY_GATE,
      f"ratio={stability_disturbed:.3f} (gate {ppg.MOTION_PERFUSION_INSTABILITY_GATE})")
check("the disturbed capture reads far less stable than the steady one",
      stability_disturbed > stability_steady * 3,
      f"{stability_disturbed:.3f} vs {stability_steady:.3f}")

# infer_motion: the OR combination the Dart EcgAnalyser.analyse performs.
usable_ppg = ppg.analyse_ppg(steady, PPG_FS)
check("low wander + steady PPG -> no inferred motion",
      not ppg.infer_motion(0.05, usable_ppg))
check("high wander alone triggers it, regardless of the PPG",
      ppg.infer_motion(ppg.MOTION_WANDER_RATIO_GATE, usable_ppg))
disturbed_ppg = ppg.analyse_ppg(disturbed, PPG_FS)
check("unstable PPG alone triggers it, even with low ECG wander",
      disturbed_ppg.usable and ppg.infer_motion(0.05, disturbed_ppg),
      f"ppg usable={disturbed_ppg.usable}")
check("with no PPG capture at all, the ECG signal alone still decides",
      not ppg.infer_motion(0.05, None) and ppg.infer_motion(0.9, None))

# --------------------------------------------------------------------------
print("\n3. Pulse deficit -- the multimodal quantity")
# --------------------------------------------------------------------------
# Sinus rhythm: every beat fills adequately, so every beat perfuses.
_, truth, r_ms, sig, _ = make_case("nsr", 0, fill_ms=None)
res = ppg.analyse_ppg(sig, PPG_FS)
f_nsr = ppg.fuse(r_ms, res.peaks * 1000.0 / PPG_FS)
check("sinus rhythm: deficit near zero", abs(f_nsr.pulse_deficit_bpm) < 3.0,
      f"deficit={f_nsr.pulse_deficit_bpm:+.2f} bpm")
check("sinus rhythm: essentially all beats perfuse",
      f_nsr.perfused_beat_fraction >= 0.95,
      f"fraction={f_nsr.perfused_beat_fraction:.3f}, "
      f"non-perfusing={f_nsr.non_perfusing_beats}")

# AF with a 500 ms filling requirement: short-coupled beats eject too little
# blood to reach the finger, which is the mechanism, not a random dropout.
deficits, fractions = [], []
for seed in range(6):
    _, truth, r_ms, sig, _ = make_case("af", 100 + seed, fill_ms=500.0)
    res = ppg.analyse_ppg(sig, PPG_FS)
    f = ppg.fuse(r_ms, res.peaks * 1000.0 / PPG_FS)
    deficits.append(f.pulse_deficit_bpm)
    fractions.append(f.perfused_beat_fraction)

deficits, fractions = np.array(deficits), np.array(fractions)
print(f"      AF deficits (bpm): {np.round(deficits, 1).tolist()}")
print(f"      AF perfused fraction: {np.round(fractions, 3).tolist()}")
check("AF: deficit clearly positive", deficits.min() > 3.0,
      f"min={deficits.min():.1f} mean={deficits.mean():.1f} bpm")
check("AF: deficit exceeds the sinus case for every seed",
      deficits.min() > abs(f_nsr.pulse_deficit_bpm) + 2,
      f"AF min {deficits.min():.1f} vs NSR {f_nsr.pulse_deficit_bpm:+.2f}")
check("AF: typical window shows non-perfusing beats",
      fractions.mean() < ppg.PERFUSED_FRACTION_LOW,
      f"mean fraction {fractions.mean():.3f} (gate {ppg.PERFUSED_FRACTION_LOW})")
check("AF: every seed perfuses worse than sinus rhythm",
      fractions.max() < f_nsr.perfused_beat_fraction,
      f"worst AF {fractions.max():.3f} vs NSR {f_nsr.perfused_beat_fraction:.3f}")

# Not every AF window has a large deficit -- it depends on how many beats happen
# to be short-coupled. That is precisely why the PPG CORROBORATES an ECG finding
# rather than detecting AF on its own, and why contracts/ppg.md §7 gives it no
# power to create a referral. A test asserting every AF window trips the gate
# would be asserting something clinically false.
n_tripped = int((fractions < ppg.PERFUSED_FRACTION_LOW).sum())
print(f"      {n_tripped}/{len(fractions)} AF windows trip the perfusion gate "
      f"- corroboration is not detection")

# --------------------------------------------------------------------------
print("\n4. Fusion refuses to answer when it should")
# --------------------------------------------------------------------------
check("no fusion when captures were not simultaneous",
      not ppg.fuse([0, 1000, 2000], [250, 1250, 2250], simultaneous=False).valid)
check("no fusion when the PPG failed its gate",
      not ppg.fuse([0, 1000, 2000], [250, 1250, 2250], ppg_usable=False).valid)
short = ppg.fuse(list(np.arange(0, 5000, 800.0)), list(np.arange(250, 5250, 800.0)))
check("no fusion on a 5 s overlap", not short.valid, f"reason={short.invalid_reason!r}")

# More pulses than heartbeats is impossible: the ECG detector missed beats.
r_sparse = list(np.arange(0, 30000, 1600.0))         # 37.5 bpm "detected"
p_dense = list(np.arange(250, 30250, 800.0))          # 75 bpm actual
imp = ppg.fuse(r_sparse, p_dense)
check("negative deficit flagged as detector failure, not a finding",
      imp.implausible and not imp.valid,
      f"deficit={imp.pulse_deficit_bpm:+.1f} bpm -> {imp.invalid_reason!r}")

# --------------------------------------------------------------------------
print("\n5. Policy: PPG escalates, never reassures")
# --------------------------------------------------------------------------


def decide(**kw):
    """Mirror of Policy.decide with the PPG branch."""
    base = pol.decide(**{k: v for k, v in kw.items()
                         if k in ("sqi", "motion", "lead_off", "gap", "rr_count",
                                  "hr", "irregularity", "cnn")})
    if base.tier is not pol.Tier.AMBER and base.tier is not pol.Tier.RED:
        return base.tier, "n/a"
    deficit = kw.get("deficit")
    perfused = kw.get("perfused")
    if deficit is None or perfused is None:
        corrob = "none"
    elif deficit >= ppg.PULSE_DEFICIT_BPM:
        corrob = "pulseDeficit"
    elif perfused < ppg.PERFUSED_FRACTION_LOW:
        corrob = "nonPerfusingBeats"
    else:
        corrob = "agreed"
    rate_abnormal = kw["hr"] < pol.K_HR_LOW or kw["hr"] > pol.K_HR_HIGH
    escalate = corrob in ("pulseDeficit", "nonPerfusingBeats")
    return (pol.Tier.RED if (rate_abnormal or escalate) else pol.Tier.AMBER), corrob


t, c = decide(irregularity=0.8, hr=72)
check("irregular + normal rate + no PPG -> AMBER", t is pol.Tier.AMBER and c == "none")

t, c = decide(irregularity=0.8, hr=72, deficit=1.0, perfused=0.99)
check("PPG agrees -> stays AMBER", t is pol.Tier.AMBER and c == "agreed")

t, c = decide(irregularity=0.8, hr=72, deficit=15.0, perfused=0.80)
check("pulse deficit -> escalates to RED", t is pol.Tier.RED and c == "pulseDeficit")

t, c = decide(irregularity=0.8, hr=72, deficit=4.0, perfused=0.70)
check("non-perfusing beats -> escalates to RED", t is pol.Tier.RED and c == "nonPerfusingBeats")

# The safety invariant: a reassuring PPG must never clear a patient.
t, _ = decide(irregularity=0.8, hr=140, deficit=0.0, perfused=1.0)
check("perfect PPG cannot downgrade a RED", t is pol.Tier.RED)

t, _ = decide(irregularity=0.2, hr=72, deficit=20.0, perfused=0.5)
check("PPG alone cannot create a referral from a regular rhythm",
      t is pol.Tier.GREEN,
      "ECG found no irregularity; PPG corroborates, it does not detect")

# --------------------------------------------------------------------------
failed = [r for r in results if r[0] == "FAIL"]
print("\n" + "=" * 70)
print(f"{len(results) - len(failed)}/{len(results)} checks passed")
for _, name, detail in failed:
    print(f"  - {name}: {detail}")
print("=" * 70)
sys.exit(1 if failed else 0)
