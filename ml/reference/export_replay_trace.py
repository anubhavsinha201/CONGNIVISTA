"""Exports one real, labelled CinC 2017 AF record as a bundled replay asset for
ReplaySource (ticket 009) - the "demo integrity" trace CLAUDE.md describes:
atrial fibrillation cannot be induced in a healthy teammate on stage, so the
demo replays a real recording through the identical on-device pipeline
instead, and says so out loud.

Deliberately NOT the same array prepare_cinc2017.py produces for training:
that path resamples AND applies FilterChain.ecgConditioning before storage
(see its to_windows() docstring - correct for training, where the model must
see exactly what the app's own filtered path would show it). A replay trace
is different: it stands in for what the ESP32 itself would have sent, so our
own SqiAnalyser/FilterChain need to do their own filtering, from genuinely
raw samples, the same as they would on a live capture. This script therefore
resamples 300 Hz -> 250 Hz and stops there.

No amplitude rescaling either. CLAUDE.md's own design choice ("Sample units:
Arbitrary ADU, not mV - nothing is calibrated against a reference") means
there is no canonical scale to match in the first place, and record A00004's
raw range (-214..1352) already sits comfortably inside SqiAnalyser's rail
threshold (+/-2040) without any adjustment - checked below, not assumed.

Run:  python ml/reference/export_replay_trace.py
"""

from __future__ import annotations

import os
import sys

import numpy as np
from scipy.io import loadmat
from scipy.signal import resample_poly

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dsp_reference as dsp  # noqa: E402

SRC_FS = 300
DST_FS = 250
RECORD = "A02501"  # CinC 2017 training2017, REFERENCE.csv label 'A' (AF)
WINDOW = 7500      # exactly 30 s at 250 Hz - one analysis window, what the app scores

# Gates this trace has to clear to be usable as a demo, from contracts/tiers.md
# section 4. Asserted below with MARGIN, not merely cleared: record A00004 shipped
# first and produced exactly 30 RR intervals against a >= 30 gate, so a single
# missed R peak on stage would have turned the flagship AF demo into a RETAKE.
# Clearing a gate by nothing is not clearing it.
K_MIN_RR_INTERVALS = 30
K_SQI_GATE = 0.5
K_RR_IRREGULARITY_GATE = 0.5
K_HR_LOW, K_HR_HIGH = 50.0, 120.0
MIN_MARGIN = 0.25  # every gate must be cleared by at least this fraction

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_ROOT = os.path.join(HERE, "..", "data", "training2017", "training2017")
OUT_DIR = os.path.join(HERE, "..", "..", "android", "app", "src", "main", "assets", "replay")


def main() -> None:
    ref_path = os.path.join(DATA_ROOT, "REFERENCE.csv")
    with open(ref_path, encoding="utf-8") as f:
        rows = dict(line.strip().split(",")[:2] for line in f)
    label = rows.get(RECORD)
    assert label == "A", f"{RECORD} is labelled {label!r}, expected 'A' (AF) - pick a different record"

    mat_path = os.path.join(DATA_ROOT, RECORD + ".mat")
    raw = np.asarray(loadmat(mat_path)["val"], dtype=np.float64).ravel()
    print(f"{RECORD}: {raw.size} samples @ {SRC_FS} Hz = {raw.size / SRC_FS:.1f} s, "
          f"range [{raw.min():.0f}, {raw.max():.0f}]")

    resampled = resample_poly(raw, DST_FS, SRC_FS)
    print(f"resampled: {resampled.size} samples @ {DST_FS} Hz = {resampled.size / DST_FS:.1f} s")

    # Sanity check, not an assumption: confirm this record's real amplitude
    # actually fits the rail threshold before shipping it as a demo asset.
    rail = 2040.0
    assert np.abs(resampled).max() < rail, (
        f"record {RECORD}'s amplitude ({np.abs(resampled).max():.0f}) would clip "
        f"SqiAnalyser's rail threshold ({rail}) - pick a different record or scale it"
    )

    resampled = resampled[:WINDOW]
    assert resampled.size == WINDOW, (
        f"{RECORD} yields only {resampled.size} samples at {DST_FS} Hz; "
        f"{WINDOW} ({WINDOW / DST_FS:.0f} s) are needed for one analysis window"
    )

    samples_int16 = np.round(resampled).astype(np.int16)

    # ---- the trace must clear every gate with room to spare -----------------
    # Run the app's own verified chain over the exact int16 the asset will hold,
    # so these numbers are what the device will actually compute, not an
    # approximation of it.
    check = samples_int16.astype(np.float64)
    sqi = dsp.analyse_sqi(check)
    peaks = np.asarray(dsp.detect_rpeaks(check, float(DST_FS))[0])
    feats = dsp.analyse_rr(np.diff(peaks) * 1000.0 / DST_FS)

    margins = {
        "rrIntervalCount": (feats.count - K_MIN_RR_INTERVALS) / K_MIN_RR_INTERVALS,
        "sqiScore": (sqi["score"] - K_SQI_GATE) / K_SQI_GATE,
        "rrIrregularityScore":
            (feats.irregularityScore - K_RR_IRREGULARITY_GATE) / K_RR_IRREGULARITY_GATE,
        "meanHr above kHrLow": (feats.meanHr - K_HR_LOW) / (K_HR_HIGH - K_HR_LOW),
        "meanHr below kHrHigh": (K_HR_HIGH - feats.meanHr) / (K_HR_HIGH - K_HR_LOW),
    }
    print(f"measured: {feats.count} RR intervals, SQI {sqi['score']:.4f}, "
          f"irregularity {feats.irregularityScore:.4f}, mean HR {feats.meanHr:.1f} bpm")
    for name, m in margins.items():
        print(f"  margin over {name:24} {m:+.1%}")
    worst = min(margins, key=margins.get)
    assert margins[worst] >= MIN_MARGIN, (
        f"{RECORD} clears '{worst}' by only {margins[worst]:.1%} "
        f"(need >= {MIN_MARGIN:.0%}) - pick a record with more headroom"
    )

    os.makedirs(OUT_DIR, exist_ok=True)
    out_path = os.path.join(OUT_DIR, f"af_{RECORD}_250hz.raw")
    samples_int16.tofile(out_path)
    print(f"wrote {samples_int16.size * 2} bytes to {os.path.abspath(out_path)}")

    meta_path = os.path.join(OUT_DIR, f"af_{RECORD}_250hz.meta.txt")
    with open(meta_path, "w") as f:
        f.write(
            f"source: PhysioNet/CinC Challenge 2017, training2017/{RECORD}.mat\n"
            f"label: AF (REFERENCE.csv)\n"
            f"format: little-endian int16, {DST_FS} Hz, {samples_int16.size} samples "
            f"({samples_int16.size / DST_FS:.1f} s)\n"
            f"processing: resampled 300->250 Hz only - NOT filtered, NOT normalised, "
            f"NOT rescaled. The app's own SqiAnalyser/FilterChain run on this exactly "
            f"as they would on a live ESP32 capture.\n"
        )
    print(f"wrote {meta_path}")


if __name__ == "__main__":
    main()
