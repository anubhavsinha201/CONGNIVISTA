"""Generates app/test/fixtures/ppg_golden_vectors.json for the Kotlin PPG/fusion
port (ticket 019, module 2).

Reuses ppg_reference.py's synth_ppg() and synth_ecg.synth_ecg() for signal
generation unchanged - that machinery is pure synthesis, no percentile
involved, so it is not affected by the discrepancy below.

IMPORTANT - does NOT call ppg_reference.perfusion_index()/perfusion_stability()
directly for the expected values. Those use np.percentile (linear
interpolation); app/lib/signal/ppg.dart uses a simple index-floor percentile
(sorted[floor(n*0.05)], matching dsp_reference.py's RR-entropy trim exactly,
which is why module 1's golden vectors matched to 1e-6). ppg_reference.py
was never updated to match, and validate_ppg.py's checks are all range-based
(e.g. "Se >= 0.95"), so this drift was never caught. Since the Kotlin port
is translating app/lib/signal/ppg.dart (the deliverable), not
ppg_reference.py, the expected values here use Dart's actual method - see
_percentile_index_floor below. Flagged in ticket 019, not silently fixed.

Run:  python ml/reference/generate_ppg_golden_vectors.py
"""

from __future__ import annotations

import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dsp_reference as dsp  # noqa: E402
import ppg_reference as ppg  # noqa: E402
import synth_ecg as synth  # noqa: E402

PPG_FS = ppg.PPG_FS


def _percentile_index_floor(sorted_arr: np.ndarray, frac: float) -> float:
    """Dart's sorted[(length * frac).floor()] - NOT np.percentile."""
    return float(sorted_arr[int(math.floor(sorted_arr.size * frac))])


def perfusion_index_dart(raw: np.ndarray, fs: float = PPG_FS) -> float:
    dc = raw.mean()
    if abs(dc) < 1e-9:
        return 0.0
    ac = dsp.filtfilt_fast(ppg.ppg_band(fs), raw)
    s = np.sort(ac)
    lo = _percentile_index_floor(s, 0.05)
    hi = _percentile_index_floor(s, 0.95)
    return abs(hi - lo) / abs(dc) * 100.0


def perfusion_stability_dart(raw: np.ndarray, fs: float = PPG_FS) -> float:
    dc = raw.mean()
    if abs(dc) < 1e-9:
        return 0.0
    ac = dsp.filtfilt_fast(ppg.ppg_band(fs), raw)
    window = round(ppg.MOTION_SUB_WINDOW_SEC * fs)
    n = raw.size // window
    if n < 2:
        return 0.0
    spreads = np.empty(n)
    for i in range(n):
        seg = np.sort(ac[i * window:(i + 1) * window])
        lo = _percentile_index_floor(seg, 0.05)
        hi = _percentile_index_floor(seg, 0.95)
        spreads[i] = abs(hi - lo)
    spreads = spreads / abs(dc) * 100.0
    mean = spreads.mean()
    if mean < 1e-9:
        return 0.0
    return float((spreads.max() - spreads.min()) / mean)


def analyse_ppg_dart(raw_ir: np.ndarray, fs: float = PPG_FS) -> ppg.PpgResult:
    """Mirrors ppg_reference.analyse_ppg but with the Dart-faithful percentile."""
    raw = np.asarray(raw_ir, dtype=float)
    if raw.size < fs * 5:
        return ppg.PpgResult(np.array([]), np.array([]), 0, 0, 0, False, 0, "Capture too short")

    pi = perfusion_index_dart(raw, fs)
    if pi < ppg.PERFUSION_GATE:
        return ppg.PpgResult(np.array([]), np.array([]), 0, 0, pi, False, 0, "Weak pulse signal")

    band = dsp.filtfilt_fast(ppg.ppg_band(fs), raw)
    peaks = ppg.detect_systolic_peaks(band, fs)
    if peaks.size < 2:
        return ppg.PpgResult(peaks, np.array([]), 0, 0, pi, False, 0, "Could not find a pulse")

    ibi = np.diff(peaks).astype(float) * 1000.0 / fs
    feats = dsp.analyse_rr(ibi)
    return ppg.PpgResult(
        peaks, ibi, feats.meanHr, feats.irregularityScore, pi,
        feats.count >= 2, feats.count,
        perfusion_stability_ratio=perfusion_stability_dart(raw, fs),
    )


def fuse_dart(ecg_peak_ms, ppg_peak_ms, ppg_usable=True, simultaneous=True) -> ppg.FusionFeatures:
    """Mirrors ppg_reference.fuse, but with Dart's actual "median" -
    sorted[len // 2], not a true median (no averaging of the two middle
    elements for an even count). ppg_reference.fuse uses np.median, which
    disagrees with Dart whenever there's an even number of matched beats.
    Only medianPttMs is affected - deficit/fraction/implausible do not
    depend on it. Same class of drift as the percentile one above."""
    if not simultaneous:
        return ppg.FusionFeatures(0, 0, 0, 0, False, invalid_reason="not simultaneous")
    if not ppg_usable:
        return ppg.FusionFeatures(0, 0, 0, 0, False, invalid_reason="PPG below perfusion gate")
    if len(ecg_peak_ms) < 2 or len(ppg_peak_ms) < 2:
        return ppg.FusionFeatures(0, 0, 0, 0, False, invalid_reason="too few beats")

    start = max(ecg_peak_ms[0], ppg_peak_ms[0] - ppg.PTT_MAX_MS)
    end = min(ecg_peak_ms[-1], ppg_peak_ms[-1] - ppg.PTT_MIN_MS)
    overlap = (end - start) / 1000.0
    if overlap < ppg.MIN_OVERLAP_SEC:
        return ppg.FusionFeatures(0, 0, 0, 0, False, invalid_reason="insufficient overlap")

    ecg_in = [t for t in ecg_peak_ms if start <= t <= end]
    ppg_in = [t for t in ppg_peak_ms if start + ppg.PTT_MIN_MS <= t <= end + ppg.PTT_MAX_MS]
    if len(ecg_in) < 2:
        return ppg.FusionFeatures(0, 0, 0, 0, False, invalid_reason="too few beats in window")

    matched, idx, ptts = 0, 0, []
    for r in ecg_in:
        while idx < len(ppg_in) and ppg_in[idx] < r + ppg.PTT_MIN_MS:
            idx += 1
        if idx < len(ppg_in) and ppg_in[idx] <= r + ppg.PTT_MAX_MS:
            ptts.append(ppg_in[idx] - r)
            matched += 1
            idx += 1

    fraction = matched / len(ecg_in)
    ptts.sort()
    median_ptt = ptts[len(ptts) // 2] if ptts else 0.0

    hr_ecg = (len(ecg_in) - 1) / overlap * 60.0
    deficit = hr_ecg * (1.0 - fraction)
    implausible = len(ppg_in) > len(ecg_in) * 1.15

    return ppg.FusionFeatures(
        deficit, fraction, len(ecg_in) - matched, median_ptt,
        valid=not implausible, implausible=implausible,
        invalid_reason="more pulses than heartbeats" if implausible else None)


def make_case(name, rhythm, seed, duration=30.0, fill_ms=None, **ppg_kw):
    ecg, truth = synth.synth_ecg(duration_s=duration, rhythm=rhythm, noise_std=8, seed=seed)
    r_ms = ppg.r_peaks_to_ms(truth, 250.0)
    sig, pulse_ms = ppg.synth_ppg(r_ms, duration, PPG_FS, perfusion_fill_ms=fill_ms, seed=seed, **ppg_kw)
    res = analyse_ppg_dart(sig, PPG_FS)
    fusion = fuse_dart(list(r_ms), list(res.peaks * 1000.0 / PPG_FS))
    return {
        "name": name,
        "samples": sig.tolist(),
        "expectedPpg": {
            "peaks": res.peaks.tolist(),
            "meanPulseRate": res.mean_pulse_rate,
            "irregularityScore": res.irregularity,
            "perfusionIndex": res.perfusion_index,
            "perfusionStabilityRatio": res.perfusion_stability_ratio,
            "usable": res.usable,
            "beatCount": res.beat_count,
            "failureReason": res.failure_reason,
        },
        "ecgPeakTimesMs": r_ms.tolist(),
        "expectedFusion": {
            "pulseDeficitBpm": fusion.pulse_deficit_bpm,
            "perfusedBeatFraction": fusion.perfused_beat_fraction,
            "nonPerfusingBeats": fusion.non_perfusing_beats,
            "medianPttMs": fusion.median_ptt_ms,
            "valid": fusion.valid,
            "implausible": fusion.implausible,
        },
    }


cases = [
    make_case("nsr_clean_pulse", "nsr", 0),
    make_case("af_deficit", "af", 100, fill_ms=500.0),
    make_case("weak_cold_finger", "nsr", 0, ac_fraction=0.0008),
]

fixture = {
    "_comment": (
        "Generated by ml/reference/generate_ppg_golden_vectors.py for the "
        "Kotlin PPG/fusion port (ticket 019). Expected values use Dart's "
        "index-floor percentile (see that script's module docstring for why "
        "this deliberately differs from ppg_reference.py's own np.percentile "
        "- a real, previously-uncaught drift, not a typo)."
    ),
    "fs": PPG_FS,
    "cases": cases,
}

out_path = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "app", "test", "fixtures", "ppg_golden_vectors.json",
)
with open(out_path, "w") as f:
    json.dump(fixture, f)

print(f"Wrote {len(cases)} cases to {os.path.abspath(out_path)}")
for c in cases:
    print(f"  {c['name']}: usable={c['expectedPpg']['usable']} "
          f"peaks={len(c['expectedPpg']['peaks'])} "
          f"PI={c['expectedPpg']['perfusionIndex']:.3f}")
