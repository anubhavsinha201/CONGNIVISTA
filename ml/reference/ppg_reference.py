"""Python mirror of app/lib/signal/ppg.dart and fusion.dart, plus a synthetic
contact-PPG generator.

Same arrangement as dsp_reference.py: the Dart is the deliverable, this is the
side that can actually be executed and checked. See contracts/ppg.md.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

import dsp_reference as dsp

PPG_FS = 100.0

# ---- constants mirrored from PpgAnalyser / FusionAnalyser / Policy ---------
PERFUSION_GATE = 0.3
MIN_BEATS = 12
MIN_IBI_MS = 250.0
MAX_IBI_MS = 2000.0
IRREGULARITY_GATE = 0.5
EDGE_GUARD_MS = 500.0

PTT_MIN_MS = 150.0
PTT_MAX_MS = 450.0  # R peak -> PPG SYSTOLIC PEAK
MIN_OVERLAP_SEC = 10.0

PULSE_DEFICIT_BPM = 10.0
PERFUSED_FRACTION_LOW = 0.90


def ppg_band(fs=PPG_FS):
    return [dsp.biquad_highpass(fs, 0.5), dsp.biquad_lowpass(fs, 5.0)]


@dataclass
class PpgResult:
    peaks: np.ndarray
    ibi_ms: np.ndarray
    mean_pulse_rate: float
    irregularity: float
    perfusion_index: float
    usable: bool
    beat_count: int
    failure_reason: str | None = None

    @property
    def prescreen(self) -> str:
        if not self.usable or self.beat_count < MIN_BEATS:
            return "unclear"
        return "irregular" if self.irregularity >= IRREGULARITY_GATE else "regular"


def perfusion_index(raw, fs=PPG_FS):
    """AC/DC x 100, using a 5-95 percentile span so one motion spike cannot
    manufacture a healthy-looking number."""
    raw = np.asarray(raw, dtype=float)
    dc = raw.mean()
    if abs(dc) < 1e-9:
        return 0.0
    ac = dsp.filtfilt_fast(ppg_band(fs), raw)
    lo, hi = np.percentile(ac, 5), np.percentile(ac, 95)
    return abs(hi - lo) / abs(dc) * 100.0


def detect_systolic_peaks(x, fs=PPG_FS):
    """A PPG pulse is a broad smooth hump, not a spike, so Pan-Tompkins'
    derivative emphasis is the wrong tool. Adaptive amplitude threshold with a
    physiological refractory period instead."""
    refractory = max(1, round(MIN_IBI_MS / 1000.0 * fs))
    median = float(np.median(x))
    upper = float(np.percentile(x, 75))
    threshold = median + 0.5 * (upper - median)

    guard = round(EDGE_GUARD_MS / 1000.0 * fs)
    peaks: list[int] = []
    for i in range(1, x.size - 1):
        # Filter edge transients are not beats. A spurious pulse inflates the
        # PPG rate and drives the deficit negative, which surfaces to the worker
        # as a false "beat detection unreliable" retake.
        if i < guard or i >= x.size - guard:
            continue
        if x[i] <= threshold:
            continue
        if x[i] <= x[i - 1] or x[i] < x[i + 1]:
            continue
        if peaks and i - peaks[-1] < refractory:
            if x[i] > x[peaks[-1]]:
                peaks[-1] = i
            continue
        peaks.append(i)
    return np.array(peaks, dtype=int)


def analyse_ppg(raw_ir, fs=PPG_FS) -> PpgResult:
    raw = np.asarray(raw_ir, dtype=float)
    if raw.size < fs * 5:
        return PpgResult(np.array([]), np.array([]), 0, 0, 0, False, 0,
                         "Capture too short")

    pi = perfusion_index(raw, fs)
    if pi < PERFUSION_GATE:
        return PpgResult(np.array([]), np.array([]), 0, 0, pi, False, 0,
                         "Weak pulse signal")

    band = dsp.filtfilt_fast(ppg_band(fs), raw)
    peaks = detect_systolic_peaks(band, fs)
    if peaks.size < 2:
        return PpgResult(peaks, np.array([]), 0, 0, pi, False, 0,
                         "Could not find a pulse")

    ibi = np.diff(peaks).astype(float) * 1000.0 / fs
    feats = dsp.analyse_rr(ibi)  # same interval statistics as the ECG path
    return PpgResult(peaks, ibi, feats.meanHr, feats.irregularityScore, pi,
                     feats.count >= 2, feats.count)


@dataclass
class FusionFeatures:
    pulse_deficit_bpm: float
    perfused_beat_fraction: float
    non_perfusing_beats: int
    median_ptt_ms: float
    valid: bool
    implausible: bool = False
    invalid_reason: str | None = None


def fuse(ecg_peak_ms, ppg_peak_ms, ppg_usable=True, simultaneous=True) -> FusionFeatures:
    if not simultaneous:
        return FusionFeatures(0, 0, 0, 0, False, invalid_reason="not simultaneous")
    if not ppg_usable:
        return FusionFeatures(0, 0, 0, 0, False, invalid_reason="PPG below perfusion gate")
    if len(ecg_peak_ms) < 2 or len(ppg_peak_ms) < 2:
        return FusionFeatures(0, 0, 0, 0, False, invalid_reason="too few beats")

    # Work on the ECG timeline, bounded to beats whose pulse could have been
    # seen. A window shared between the two streams is biased by one beat,
    # because the events are separated by the transit time -- that produced a
    # NEGATIVE deficit on healthy subjects, which reads as a detector failure.
    start = max(ecg_peak_ms[0], ppg_peak_ms[0] - PTT_MAX_MS)
    end = min(ecg_peak_ms[-1], ppg_peak_ms[-1] - PTT_MIN_MS)
    overlap = (end - start) / 1000.0
    if overlap < MIN_OVERLAP_SEC:
        return FusionFeatures(0, 0, 0, 0, False, invalid_reason="insufficient overlap")

    ecg_in = [t for t in ecg_peak_ms if start <= t <= end]
    ppg_in = [t for t in ppg_peak_ms if start + PTT_MIN_MS <= t <= end + PTT_MAX_MS]
    if len(ecg_in) < 2:
        return FusionFeatures(0, 0, 0, 0, False, invalid_reason="too few beats in window")

    matched, idx, ptts = 0, 0, []
    for r in ecg_in:
        while idx < len(ppg_in) and ppg_in[idx] < r + PTT_MIN_MS:
            idx += 1
        if idx < len(ppg_in) and ppg_in[idx] <= r + PTT_MAX_MS:
            ptts.append(ppg_in[idx] - r)
            matched += 1
            idx += 1

    fraction = matched / len(ecg_in)
    median_ptt = float(np.median(ptts)) if ptts else 0.0

    # Derived from the per-beat matching, not from two independently windowed
    # rates. Same finding expressed as a rate; a negative value is now
    # structurally impossible.
    hr_ecg = (len(ecg_in) - 1) / overlap * 60.0
    deficit = hr_ecg * (1.0 - fraction)

    # A finger cannot pulse without a heartbeat: more pulses than beats means
    # the R-peak detector missed some.
    implausible = len(ppg_in) > len(ecg_in) * 1.15

    return FusionFeatures(
        deficit, fraction, len(ecg_in) - matched, median_ptt,
        valid=not implausible, implausible=implausible,
        invalid_reason="more pulses than heartbeats" if implausible else None)


# --------------------------------------------------------------------------
# Synthetic contact PPG
# --------------------------------------------------------------------------

SYSTOLIC_PEAK_OFFSET_S = 0.14  # where the systolic peak sits inside the template


def _pulse_template(fs, width_s=0.55):
    """One systolic upstroke plus dicrotic notch, roughly the shape a fingertip
    reflectance sensor produces."""
    t = np.arange(0, width_s, 1 / fs)
    systolic = np.exp(-((t - 0.14) ** 2) / (2 * 0.055 ** 2))
    dicrotic = 0.32 * np.exp(-((t - 0.34) ** 2) / (2 * 0.060 ** 2))
    return systolic + dicrotic


def synth_ppg(
    r_peak_times_ms,
    duration_s,
    fs=PPG_FS,
    ptt_ms=180.0,  # R peak -> pulse FOOT; the systolic peak is ~140 ms later
    dc_level=100000.0,
    ac_fraction=0.02,
    noise_frac=0.0015,
    perfusion_fill_ms=None,
    seed=0,
):
    """Generate a PPG that is physiologically coupled to a given R-peak series.

    ``perfusion_fill_ms`` is the model of pulse deficit: a beat only produces a
    peripheral pulse if the PRECEDING interval gave the ventricle enough time to
    fill. Set it to None for a normally perfusing rhythm, or to e.g. 500 ms so
    that short-coupled beats in AF eject too little blood to reach the finger.
    This is the mechanism contracts/ppg.md section 1 describes, simulated
    directly rather than approximated by dropping random pulses.

    Returns (signal, pulse_times_ms).
    """
    rng = np.random.default_rng(seed)
    n = int(duration_s * fs)
    ac_amp = dc_level * ac_fraction

    template = _pulse_template(fs)
    sig = np.zeros(n)
    pulse_times = []

    r = np.asarray(r_peak_times_ms, dtype=float)
    for i, t_r in enumerate(r):
        if perfusion_fill_ms is not None and i > 0:
            if (t_r - r[i - 1]) < perfusion_fill_ms:
                continue  # underfilled ventricle -> no peripheral pulse
        t_pulse = t_r + ptt_ms
        start = int(round(t_pulse / 1000.0 * fs))
        if start < 0 or start + template.size > n:
            continue
        # Weaker filling gives a smaller pulse even when one does reach the finger.
        scale = 1.0
        if i > 0:
            ratio = (t_r - r[i - 1]) / 800.0
            scale = float(np.clip(ratio, 0.55, 1.15))
        sig[start:start + template.size] += ac_amp * scale * template
        # Record the SYSTOLIC PEAK, which is what detect_systolic_peaks finds
        # and what the fusion matches against -- not the pulse foot.
        pulse_times.append(t_pulse + SYSTOLIC_PEAK_OFFSET_S * 1000.0)

    sig += dc_level
    tt = np.arange(n) / fs
    sig += dc_level * 0.004 * np.sin(2 * np.pi * 0.25 * tt)  # respiratory sway
    sig += rng.normal(0, dc_level * noise_frac, n)
    return sig, np.array(pulse_times)


def r_peaks_to_ms(peaks, fs):
    return np.asarray(peaks, dtype=float) * 1000.0 / fs
