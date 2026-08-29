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

import numpy as np
from scipy.io import loadmat
from scipy.signal import resample_poly

SRC_FS = 300
DST_FS = 250
RECORD = "A00004"  # CinC 2017 training2017, REFERENCE.csv label 'A' (AF), 30 s

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

    samples_int16 = np.round(resampled).astype(np.int16)
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
