"""Synthetic single-lead ECG with known ground-truth R-peak positions.

Real ECG is the eventual test set, but synthetic signals give something real
recordings cannot: exact R-peak times. That makes it possible to measure
detector sensitivity and positive predictive value without a hand-annotated
database, and to isolate one degradation at a time -- mains hum alone, motion
alone, a detached electrode alone.

Amplitudes are in the same arbitrary units the hardware delivers (ADC value
minus 2048, so the rails are +/- 2048). See contracts/ble.md.
"""

from __future__ import annotations

import numpy as np

FS = 250.0


def _gaussian(t, centre, amplitude, width):
    return amplitude * np.exp(-((t - centre) ** 2) / (2 * width ** 2))


def _beat_template(fs=FS, r_amplitude=600.0, with_p_wave=True):
    """One PQRST complex, 800 ms wide, R peak at the centre."""
    dur = 0.8
    t = np.arange(0, dur, 1 / fs)
    centre = dur / 2
    wave = np.zeros_like(t)

    if with_p_wave:
        wave += _gaussian(t, centre - 0.20, 0.12 * r_amplitude, 0.022)
    wave += _gaussian(t, centre - 0.025, -0.16 * r_amplitude, 0.0075)  # Q
    wave += _gaussian(t, centre, r_amplitude, 0.0090)                  # R
    wave += _gaussian(t, centre + 0.030, -0.25 * r_amplitude, 0.0085)  # S
    wave += _gaussian(t, centre + 0.20, 0.28 * r_amplitude, 0.038)     # T
    return wave, int(round(centre * fs))


def _rr_series_nsr(n, hr=72.0, rng=None):
    """Normal sinus rhythm: small respiratory sinus arrhythmia only."""
    rng = rng or np.random.default_rng(0)
    base = 60.0 / hr
    breathing = 0.03 * base * np.sin(2 * np.pi * np.arange(n) / 12.0)
    jitter = rng.normal(0, 0.008 * base, n)
    return base + breathing + jitter


def _rr_series_af(n, mean_hr=96.0, rng=None):
    """Atrial fibrillation: irregularly irregular ventricular response.

    Modelled as a lognormal around the mean interval, which reproduces the
    right-skewed RR histogram seen in AF, with no periodic component -- the
    absence of any underlying rhythm is the whole point.
    """
    rng = rng or np.random.default_rng(1)
    base = 60.0 / mean_hr
    rr = rng.lognormal(mean=np.log(base), sigma=0.22, size=n)
    return np.clip(rr, 0.32, 1.60)


def synth_ecg(
    duration_s=30.0,
    rhythm="nsr",
    fs=FS,
    r_amplitude=600.0,
    noise_std=8.0,
    mains_amplitude=0.0,
    wander_amplitude=0.0,
    seed=0,
):
    """Returns (signal, r_peak_indices).

    ``rhythm`` is "nsr" or "af". AF suppresses the P wave, as it does clinically.
    """
    rng = np.random.default_rng(seed)
    n_samples = int(duration_s * fs)
    sig = np.zeros(n_samples)

    n_beats = int(duration_s / 0.5) + 4
    rr = _rr_series_nsr(n_beats, rng=rng) if rhythm == "nsr" else _rr_series_af(n_beats, rng=rng)

    template, r_offset = _beat_template(fs, r_amplitude, with_p_wave=(rhythm == "nsr"))

    peaks = []
    t = 1.0  # first beat one second in, so the window does not start mid-complex
    for interval in rr:
        r_idx = int(round(t * fs))
        start = r_idx - r_offset
        if start < 0:
            t += interval
            continue
        end = start + template.size
        if end > n_samples:
            break
        sig[start:end] += template
        peaks.append(r_idx)
        t += interval

    if wander_amplitude > 0:
        tt = np.arange(n_samples) / fs
        sig += wander_amplitude * (np.sin(2 * np.pi * 0.25 * tt)
                                   + 0.6 * np.sin(2 * np.pi * 0.13 * tt + 1.1))
    if mains_amplitude > 0:
        tt = np.arange(n_samples) / fs
        sig += mains_amplitude * np.sin(2 * np.pi * 50.0 * tt)
    if noise_std > 0:
        sig += rng.normal(0, noise_std, n_samples)

    return sig, np.array(peaks, dtype=int)


def with_flatline(sig, start_s, duration_s, fs=FS):
    """Simulate an electrode coming off mid-capture."""
    out = sig.copy()
    a, b = int(start_s * fs), int((start_s + duration_s) * fs)
    out[a:min(b, out.size)] = out[a] if a < out.size else 0.0
    return out


def with_clipping(sig, rail=2048.0):
    return np.clip(sig, -rail, rail)


def match_peaks(detected, truth, tolerance_samples):
    """Greedy nearest-neighbour match. Returns (tp, fp, fn)."""
    detected, truth = list(detected), list(truth)
    used = set()
    tp = 0
    for d in detected:
        best, best_dist = None, tolerance_samples + 1
        for i, t in enumerate(truth):
            if i in used:
                continue
            dist = abs(d - t)
            if dist < best_dist:
                best, best_dist = i, dist
        if best is not None and best_dist <= tolerance_samples:
            used.add(best)
            tp += 1
    return tp, len(detected) - tp, len(truth) - tp
