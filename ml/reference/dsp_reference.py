"""Python mirror of the Dart DSP layer in app/lib/signal/.

Why this file exists
--------------------
The deliverable is the Dart code. This is a line-for-line reference
implementation of the same maths, used for three things:

1. Verifying the algorithms are correct at all, using scipy as an independent
   check on the filter design.
2. Tuning the RR-irregularity thresholds against MIT-BIH AFDB.
3. Emitting golden test vectors that the Dart unit tests assert against, so
   the two implementations are pinned to each other.

If you change a constant here, change it in the Dart too, then regenerate the
golden vectors. They will fail loudly if the two drift apart. That is the point.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, asdict

import numpy as np

FS = 250.0


# --------------------------------------------------------------------------
# Biquad filters -- mirrors app/lib/signal/filters.dart
# --------------------------------------------------------------------------

def biquad_lowpass(fs: float, f0: float, q: float = 1 / math.sqrt(2)):
    w0 = 2 * math.pi * f0 / fs
    cw, alpha = math.cos(w0), math.sin(w0) / (2 * q)
    a0 = 1 + alpha
    b = np.array([(1 - cw) / 2, 1 - cw, (1 - cw) / 2]) / a0
    a = np.array([1.0, -2 * cw / a0, (1 - alpha) / a0])
    return b, a


def biquad_highpass(fs: float, f0: float, q: float = 1 / math.sqrt(2)):
    w0 = 2 * math.pi * f0 / fs
    cw, alpha = math.cos(w0), math.sin(w0) / (2 * q)
    a0 = 1 + alpha
    b = np.array([(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]) / a0
    a = np.array([1.0, -2 * cw / a0, (1 - alpha) / a0])
    return b, a


def biquad_notch(fs: float, f0: float, q: float = 30.0):
    w0 = 2 * math.pi * f0 / fs
    cw, alpha = math.cos(w0), math.sin(w0) / (2 * q)
    a0 = 1 + alpha
    b = np.array([1.0, -2 * cw, 1.0]) / a0
    a = np.array([1.0, -2 * cw / a0, (1 - alpha) / a0])
    return b, a


def _df2t_forward(b, a, x):
    """Direct-form II transposed, with steady-state initialisation."""
    b0, b1, b2 = b
    a1, a2 = a[1], a[2]
    gain, denom = b0 + b1 + b2, 1 + a1 + a2
    x0 = x[0]
    y0 = 0.0 if abs(denom) < 1e-12 else x0 * gain / denom
    s2 = b2 * x0 - a2 * y0
    s1 = b1 * x0 - a1 * y0 + s2

    out = np.empty_like(x)
    for i, xi in enumerate(x):
        y = b0 * xi + s1
        s1 = b1 * xi - a1 * y + s2
        s2 = b2 * xi - a2 * y
        out[i] = y
    return out


def filtfilt_chain(sections, x, pad_len_max=750):
    """Zero-phase filtering through a cascade of biquads."""
    x = np.asarray(x, dtype=float)
    if x.size < 4:
        return x.copy()

    pad = min(x.size - 1, pad_len_max)
    left = 2 * x[0] - x[pad:0:-1]
    right = 2 * x[-1] - x[-2:-pad - 2:-1]
    y = np.concatenate([left, x, right])

    for _ in range(2):
        for b, a in sections:
            y = _df2t_forward(b, a, y)
        y = y[::-1].copy()

    return y[pad:pad + x.size]


def filtfilt_fast(sections, x):
    """Vectorised equivalent of filtfilt_chain, for bulk dataset preprocessing.

    filtfilt_chain is a per-sample Python loop because it mirrors the Dart line for
    line, which is what makes it useful as a reference -- and far too slow for
    thousands of records. This builds the same biquads into an SOS array and hands
    them to scipy.

    Not bit-identical to filtfilt_chain: scipy uses Gustafsson-style edge handling
    rather than our odd-reflection padding, so the two differ slightly in the first
    and last fraction of a second. validate_dsp.py checks the interiors agree.
    Use this for preparing training data; use filtfilt_chain when pinning the Dart.
    """
    from scipy import signal as _sps

    x = np.asarray(x, dtype=float)
    sos = np.array([[b[0], b[1], b[2], 1.0, a[1], a[2]] for b, a in sections])
    # scipy's default padlen is ~20 samples for three sections. A 0.5 Hz highpass
    # rings for seconds, so that pad is far too short and the edge error bleeds
    # well into the interior. Match filtfilt_chain's 750-sample (3 s) pad.
    padlen = min(750, x.size - 1)
    return _sps.sosfiltfilt(sos, x, padtype="odd", padlen=padlen)


def ecg_conditioning(fs=FS):
    return [biquad_highpass(fs, 0.5), biquad_lowpass(fs, 40.0), biquad_notch(fs, 50.0)]


def qrs_band(fs=FS):
    return [biquad_highpass(fs, 5.0), biquad_lowpass(fs, 15.0)]


# --------------------------------------------------------------------------
# Pan-Tompkins -- mirrors app/lib/signal/pan_tompkins.dart
# --------------------------------------------------------------------------

def _derivative(x):
    out = np.zeros_like(x)
    out[4:] = (2 * x[4:] + x[3:-1] - x[1:-3] - 2 * x[:-4]) / 8.0
    return out


def _centred_moving_average(x, window):
    half = window // 2
    csum = np.concatenate([[0.0], np.cumsum(x)])
    n = x.size
    idx = np.arange(n)
    lo = np.clip(idx - half, 0, n)
    hi = np.clip(idx - half + window, 0, n)
    counts = np.maximum(hi - lo, 1)
    return (csum[hi] - csum[lo]) / counts


def _local_maxima(x, min_distance):
    peaks = []
    for i in range(1, x.size - 1):
        if x[i] > x[i - 1] and x[i] >= x[i + 1]:
            if peaks and i - peaks[-1] < min_distance:
                if x[i] > x[peaks[-1]]:
                    peaks[-1] = i
            else:
                peaks.append(i)
    return peaks


def detect_rpeaks(raw, fs=FS, fast=False):
    """Detect R peaks. Set fast=True to use the vectorised filter path.

    fast=True is for bulk evaluation over thousands of windows; the two paths
    agree to machine precision in the interior (checked in validate_dsp.py).
    Leave it False when pinning the Dart via golden vectors.
    """
    raw = np.asarray(raw, dtype=float)
    if raw.size < int(fs):
        return np.array([], dtype=int), np.zeros(0), np.zeros(0)

    mwi_window = max(1, round(0.150 * fs))
    refractory = round(0.200 * fs)
    twave_window = round(0.360 * fs)
    refine = max(1, round(0.060 * fs))

    band = (filtfilt_fast if fast else filtfilt_chain)(qrs_band(fs), raw)
    deriv = _derivative(band)
    integrated = _centred_moving_average(deriv ** 2, mwi_window)

    candidates = _local_maxima(integrated, refractory)
    if not candidates:
        return np.array([], dtype=int), band, integrated

    learn_end = min(integrated.size, int(2 * fs))
    spki = integrated[:learn_end].max() / 3.0
    npki = integrated[:learn_end].mean() / 2.0
    t1 = npki + 0.25 * (spki - npki)

    qrs, rr_recent, rr_avg = [], [], 0.0

    def max_slope(centre):
        lo, hi = max(0, centre - refine), min(deriv.size - 1, centre + refine)
        return np.abs(deriv[lo:hi + 1]).max() if hi >= lo else 0.0

    def accept(idx, amp, via_searchback):
        nonlocal spki, rr_avg
        if qrs:
            rr_recent.append(float(idx - qrs[-1]))
            if len(rr_recent) > 8:
                rr_recent.pop(0)
            rr_avg = sum(rr_recent) / len(rr_recent)
        qrs.append(idx)
        spki = (0.25 * amp + 0.75 * spki) if via_searchback else (0.125 * amp + 0.875 * spki)

    for ci, idx in enumerate(candidates):
        amp = integrated[idx]

        if qrs and rr_avg > 0 and (idx - qrs[-1]) > 1.66 * rr_avg:
            t2 = 0.5 * t1
            best_idx, best_amp = -1, 0.0
            k = ci - 1
            while k >= 0 and candidates[k] > qrs[-1]:
                c = candidates[k]
                if c - qrs[-1] >= refractory and integrated[c] > t2 and integrated[c] > best_amp:
                    best_amp, best_idx = integrated[c], c
                k -= 1
            if best_idx >= 0:
                accept(best_idx, best_amp, True)

        if amp > t1:
            if qrs and (idx - qrs[-1]) < twave_window:
                if max_slope(idx) < 0.5 * max_slope(qrs[-1]):
                    npki = 0.125 * amp + 0.875 * npki
                    t1 = npki + 0.25 * (spki - npki)
                    continue
            if qrs and (idx - qrs[-1]) < refractory:
                continue
            accept(idx, amp, False)
        else:
            npki = 0.125 * amp + 0.875 * npki
        t1 = npki + 0.25 * (spki - npki)

    qrs.sort()

    refined = []
    for idx in qrs:
        lo, hi = max(0, idx - refine), min(band.size - 1, idx + refine)
        best = lo + int(np.argmax(np.abs(band[lo:hi + 1])))
        if not refined or best > refined[-1]:
            refined.append(best)

    return np.array(refined, dtype=int), band, integrated


def rr_intervals_ms(peaks, fs=FS):
    if len(peaks) < 2:
        return np.zeros(0)
    return np.diff(np.asarray(peaks, dtype=float)) * 1000.0 / fs


# --------------------------------------------------------------------------
# RR features -- mirrors app/lib/signal/rr_features.dart
# --------------------------------------------------------------------------

MIN_PLAUSIBLE_RR_MS = 300.0
MAX_PLAUSIBLE_RR_MS = 2000.0
HISTOGRAM_BINS = 16

# MEASURED 2026-08-30 against MIT-BIH AFDB (23 patients, all usable records).
# See app/lib/signal/rr_features.dart's doc comment for the full derivation
# and ml/reference/tune_rr_thresholds.py / ml/artifacts/rr_threshold_fit.json
# for the fitting script and its output. Both must be changed together.
N_RMSSD_CENTRE, N_RMSSD_WIDTH = 0.1938, 0.0565
PNN50_CENTRE, PNN50_WIDTH = 0.4775, 0.1023
ENTROPY_CENTRE, ENTROPY_WIDTH = 0.8373, 0.0508
W_RMSSD, W_PNN50, W_ENTROPY = 0.4, 0.3, 0.3


@dataclass
class RrFeatures:
    count: int
    meanRrMs: float
    meanHr: float
    rmssdMs: float
    normalisedRmssd: float
    pnn50: float
    normalisedShannonEntropy: float
    irregularityScore: float
    rejectedIntervals: int


def _logistic(x, centre, width):
    return 1.0 / (1.0 + math.exp(-(x - centre) / width))


def _normalised_shannon_entropy(rr):
    s = np.sort(rr)
    trim = int(math.floor(s.size * 0.05))
    lo, hi = s[trim], s[s.size - 1 - trim]
    rng = hi - lo
    if rng <= 0:
        return 0.0
    inside = rr[(rr >= lo) & (rr <= hi)]
    if inside.size == 0:
        return 0.0
    bins = np.minimum(((inside - lo) / rng * HISTOGRAM_BINS).astype(int), HISTOGRAM_BINS - 1)
    counts = np.bincount(bins, minlength=HISTOGRAM_BINS)
    p = counts[counts > 0] / inside.size
    h = float(-(p * np.log(p)).sum())
    return min(max(h / math.log(HISTOGRAM_BINS), 0.0), 1.0)


def analyse_rr(rr_ms) -> RrFeatures:
    rr_ms = np.asarray(rr_ms, dtype=float)
    mask = (rr_ms >= MIN_PLAUSIBLE_RR_MS) & (rr_ms <= MAX_PLAUSIBLE_RR_MS)
    clean, rejected = rr_ms[mask], int((~mask).sum())

    if clean.size < 2:
        return RrFeatures(0, 0, 0, 0, 0, 0, 0, 0, rejected)

    n = clean.size
    mean_rr = float(clean.mean())
    diffs = np.diff(clean)
    rmssd = float(math.sqrt((diffs ** 2).sum() / (n - 1)))
    pnn50 = float((np.abs(diffs) > 50).sum() / (n - 1))
    n_rmssd = rmssd / mean_rr if mean_rr > 0 else 0.0
    entropy = _normalised_shannon_entropy(clean)

    score = min(max(
        W_RMSSD * _logistic(n_rmssd, N_RMSSD_CENTRE, N_RMSSD_WIDTH)
        + W_PNN50 * _logistic(pnn50, PNN50_CENTRE, PNN50_WIDTH)
        + W_ENTROPY * _logistic(entropy, ENTROPY_CENTRE, ENTROPY_WIDTH),
        0.0), 1.0)

    return RrFeatures(
        count=n,
        meanRrMs=mean_rr,
        meanHr=60000.0 / mean_rr if mean_rr > 0 else 0.0,
        rmssdMs=rmssd,
        normalisedRmssd=n_rmssd,
        pnn50=pnn50,
        normalisedShannonEntropy=entropy,
        irregularityScore=score,
        rejectedIntervals=rejected,
    )


# --------------------------------------------------------------------------
# SQI -- mirrors app/lib/signal/sqi.dart
# --------------------------------------------------------------------------

RAIL_MAGNITUDE = 2040.0
FLATLINE_RUN_SECONDS = 0.05
SATURATION_FAIL = 0.02


def _goertzel_power(x, k):
    w = 2 * math.pi * k / x.size
    coeff = 2 * math.cos(w)
    s1 = s2 = 0.0
    for v in x:
        s0 = v + coeff * s1 - s2
        s2, s1 = s1, s0
    return s1 * s1 + s2 * s2 - coeff * s1 * s2


def _band_power(x, lo_hz, hi_hz, fs=FS):
    n = x.size
    k_lo = max(1, int(math.floor(lo_hz * n / fs)))
    k_hi = min(int(math.ceil(hi_hz * n / fs)), n // 2)
    total = sum(_goertzel_power(x, k) for k in range(k_lo, k_hi + 1))
    return total / (n * n / 2)


def _flatline_fraction(x, fs=FS):
    min_run = max(2, round(FLATLINE_RUN_SECONDS * fs))
    flat, run_start = 0, 0
    for i in range(1, x.size + 1):
        continues = i < x.size and abs(x[i] - x[i - 1]) < 1e-9
        if not continues:
            if i - run_start >= min_run:
                flat += i - run_start
            run_start = i
    return flat / x.size


def _low_frequency_power(x, fs=FS):
    """Power below 0.5 Hz, as the residual after a zero-phase 0.5 Hz highpass.

    NOT a moving average. A moving average has a sinc response and at 0.25 Hz
    passes only ~40% of the power it is meant to measure, so the ratio
    saturates around 0.55 however severe the drift -- the detector
    under-reports the exact condition it exists to catch.
    """
    hp = filtfilt_chain([biquad_highpass(fs, 0.5)], x)
    return float((x - hp).var())


def analyse_sqi(raw, fs=FS):
    raw = np.asarray(raw, dtype=float)
    if raw.size < int(fs):
        return {"score": 0.0, "saturationFraction": 0.0, "flatlineFraction": 0.0,
                "powerlineRatio": 0.0, "baselineWanderRatio": 0.0,
                "failureReason": "Capture too short to assess"}

    saturation = float((np.abs(raw) >= RAIL_MAGNITUDE).sum() / raw.size)
    flatline = _flatline_fraction(raw, fs)
    total = float(raw.var())
    powerline = _band_power(raw, 48, 52, fs) / total if total > 0 else 0.0
    wander = _low_frequency_power(raw, fs) / total if total > 0 else 0.0

    score = 1.0
    score *= 1.0 - min(1.0, saturation / SATURATION_FAIL)
    score *= 1.0 - min(1.0, flatline / 0.10)
    score *= 1.0 - min(1.0, powerline / 0.50)
    score *= 1.0 - min(1.0, wander / 0.80)
    score = min(max(score, 0.0), 1.0)

    reason = None
    if flatline >= 0.10:
        reason = "Electrode contact lost"
    elif saturation >= SATURATION_FAIL:
        reason = "Signal clipping - check electrode placement"
    elif powerline >= 0.50:
        reason = "Mains interference - move away from wiring, unplug the charger"
    elif wander >= 0.80:
        reason = "Baseline drift - ask the patient to stay still"

    return {"score": score, "saturationFraction": saturation,
            "flatlineFraction": flatline, "powerlineRatio": powerline,
            "baselineWanderRatio": wander, "failureReason": reason}


def features_as_dict(f: RrFeatures) -> dict:
    return asdict(f)
