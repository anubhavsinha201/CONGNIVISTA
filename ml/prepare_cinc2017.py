"""Turn the raw CinC 2017 training set into fixed 250 Hz windows for the CNN.

Run:  python ml/prepare_cinc2017.py

Produces ml/data/cinc2017_250hz.npz, consumed by ml/train_af_cnn.py. This step needs
only numpy and scipy, so it is deliberately independent of TensorFlow and of where
training eventually runs.

Decisions baked in here, all of which have to match the Dart side:

1. RESAMPLED 300 -> 250 Hz. You must train at the rate you deploy at. Our hardware
   samples at 250 Hz (contracts/ble.md), so the model must learn on 250 Hz data or it
   sees a systematically different signal at inference than it did in training. This is
   docs/PRODUCT.md section 6's own argument, applied one level earlier. 300 -> 250 is
   exactly 5/6, so resample_poly is exact with no arbitrary interpolation.

2. LABELS: AF vs (Normal + Other). Noisy dropped.
   Keeping "Other" makes this the hard problem on purpose. "Other" is where the ectopy
   lives, and frequent PACs/PVCs raise RMSSD, pNN50 and Shannon entropy exactly as
   fibrillation does -- so ectopy is the dominant false-positive source for the rule
   detector in app/lib/signal/rr_features.dart. Separating AF from other irregular
   rhythms is the one clinically useful thing the CNN can do that the rules cannot.
   Training AF-vs-Normal would delete the CNN's reason to exist.

   Dropping "Noisy" is justified by the SQI gate, not by convenience: those windows are
   routed to RETAKE before inference ever runs (contracts/tiers.md), so the deployed
   model never sees them. Training distribution matches deployment by construction.

3. PER-WINDOW Z-SCORE NORMALISATION. The AD8232's gain is not calibrated and samples are
   arbitrary units (contracts/ble.md), so the model must not depend on absolute
   amplitude. THIS MUST BE REIMPLEMENTED IDENTICALLY IN DART before the model is wired
   into the app -- see contracts/model.md.

4. RECORD-DISJOINT SPLIT, NOT PATIENT-DISJOINT.
   PhysioNet does not publish subject identifiers for CinC 2017, so record-level is the
   strongest split available. Report it as record-disjoint. Do NOT describe it as
   patient-disjoint (docs/PRODUCT.md section 10). All windows cut from one record stay
   in the same split, so there is no leakage at the level we can actually control.
"""

from __future__ import annotations

import json
import os
import sys
import zipfile

import numpy as np
from scipy.io import loadmat
from scipy.signal import resample_poly

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "reference"))
import dsp_reference as dsp  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
ZIP_PATH = os.path.join(DATA, "training2017.zip")
EXTRACT_DIR = os.path.join(DATA, "training2017")
OUT_PATH = os.path.join(DATA, "cinc2017_250hz.npz")

SRC_FS = 300
DST_FS = 250
WINDOW_SEC = 30
WINDOW = WINDOW_SEC * DST_FS  # 7500

SEED = 20260828
VAL_FRAC, TEST_FRAC = 0.15, 0.15


def extract() -> str:
    ref = os.path.join(EXTRACT_DIR, "REFERENCE.csv")
    if os.path.exists(ref):
        return EXTRACT_DIR
    nested = os.path.join(EXTRACT_DIR, "training2017", "REFERENCE.csv")
    if os.path.exists(nested):
        return os.path.dirname(nested)

    if not os.path.exists(ZIP_PATH):
        sys.exit(f"Missing {ZIP_PATH}. Download it first.")
    print(f"extracting {ZIP_PATH} ...")
    with zipfile.ZipFile(ZIP_PATH) as z:
        z.extractall(EXTRACT_DIR)

    for cand in (EXTRACT_DIR, os.path.join(EXTRACT_DIR, "training2017")):
        if os.path.exists(os.path.join(cand, "REFERENCE.csv")):
            return cand
    sys.exit("REFERENCE.csv not found after extraction")


def read_reference(root: str) -> list[tuple[str, str]]:
    rows = []
    with open(os.path.join(root, "REFERENCE.csv"), encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            name, label = line.split(",")[:2]
            rows.append((name, label))
    return rows


CONDITIONING = dsp.ecg_conditioning(DST_FS)


def to_windows(sig: np.ndarray) -> list[np.ndarray]:
    """Resample to 250 Hz, apply the app's conditioning filter, cut 30 s windows.

    The conditioning chain (0.5-40 Hz + 50 Hz notch) is the SAME one the app runs via
    FilterChain.ecgConditioning before inference. Training on unfiltered data and
    inferring on filtered data would be the identical train/deploy mismatch as training
    at the wrong sample rate -- the model would learn on one signal and be shown
    another. Filtering happens on the whole record before windowing, so no window
    carries a filter edge transient.

    Records longer than 30 s yield several non-overlapping windows, which is free extra
    training data. Records shorter than 30 s are wrap-padded rather than zero-padded: a
    block of zeros is a flatline, and a flatline is the signature this pipeline uses for
    a detached electrode. Teaching the model that AF recordings contain flat segments
    would be teaching it exactly the wrong thing.
    """
    sig = resample_poly(sig.astype(np.float64), DST_FS, SRC_FS)
    if sig.size > 12:
        sig = dsp.filtfilt_fast(CONDITIONING, sig)
    if sig.size < WINDOW:
        sig = np.pad(sig, (0, WINDOW - sig.size), mode="wrap")
        return [sig[:WINDOW]]
    n = sig.size // WINDOW
    return [sig[i * WINDOW:(i + 1) * WINDOW] for i in range(n)]


def normalise(w: np.ndarray) -> np.ndarray:
    """Per-window z-score. Must be mirrored exactly in Dart -- see contracts/model.md."""
    mu = w.mean()
    sd = w.std()
    if sd < 1e-8:
        return np.zeros_like(w, dtype=np.float32)
    return ((w - mu) / sd).astype(np.float32)


def main() -> None:
    root = extract()
    rows = read_reference(root)
    print(f"records in REFERENCE.csv: {len(rows)}")

    counts = {}
    for _, lab in rows:
        counts[lab] = counts.get(lab, 0) + 1
    print("raw label counts:", counts)

    kept, dropped_noisy, missing = [], 0, 0
    for name, lab in rows:
        if lab == "~":
            dropped_noisy += 1
            continue
        if lab not in ("N", "A", "O"):
            continue
        path = os.path.join(root, name + ".mat")
        if not os.path.exists(path):
            missing += 1
            continue
        kept.append((name, lab, path))

    print(f"dropped noisy: {dropped_noisy}   missing .mat: {missing}   usable: {len(kept)}")

    rng = np.random.default_rng(SEED)
    order = rng.permutation(len(kept))

    # Split at RECORD level before windowing, so every window cut from one record lands
    # in the same split. Stratified on label so the ~9% AF rate is preserved in each.
    by_label: dict[str, list[int]] = {}
    for idx in order:
        by_label.setdefault(kept[idx][1], []).append(int(idx))

    split_of: dict[int, str] = {}
    for lab, idxs in by_label.items():
        n = len(idxs)
        n_test = int(round(n * TEST_FRAC))
        n_val = int(round(n * VAL_FRAC))
        for i, idx in enumerate(idxs):
            split_of[idx] = "test" if i < n_test else ("val" if i < n_test + n_val else "train")

    buckets: dict[str, dict[str, list]] = {
        s: {"X": [], "y": [], "rec": []} for s in ("train", "val", "test")
    }

    for i, (name, lab, path) in enumerate(kept):
        if i % 1000 == 0:
            print(f"  {i}/{len(kept)} ...", flush=True)
        try:
            sig = np.asarray(loadmat(path)["val"], dtype=np.float64).ravel()
        except Exception as exc:
            print(f"  skip {name}: {exc}")
            continue
        y = 1 if lab == "A" else 0
        split = split_of[i]
        for w in to_windows(sig):
            buckets[split]["X"].append(normalise(w))
            buckets[split]["y"].append(y)
            buckets[split]["rec"].append(name)

    out = {}
    meta = {
        "source": "PhysioNet/CinC Challenge 2017 training set",
        "fs": DST_FS,
        "window_samples": WINDOW,
        "window_seconds": WINDOW_SEC,
        "resampled_from_hz": SRC_FS,
        "conditioning": "0.5-40 Hz bandpass + 50 Hz notch, zero-phase (matches FilterChain.ecgConditioning)",
        "normalisation": "per-window z-score (mean 0, std 1)",
        "positive_class": "A (atrial fibrillation)",
        "negative_class": "N + O (normal and other rhythm)",
        "dropped_class": "~ (noisy) - excluded because the SQI gate routes these to RETAKE",
        "split": "record-disjoint, stratified by label",
        "split_caveat": (
            "RECORD-disjoint, not patient-disjoint: PhysioNet does not publish subject "
            "identifiers for CinC 2017. Report it as record-disjoint."
        ),
        "seed": SEED,
    }

    print()
    for s in ("train", "val", "test"):
        X = np.stack(buckets[s]["X"]).astype(np.float32)
        y = np.asarray(buckets[s]["y"], dtype=np.int8)
        out[f"X_{s}"] = X
        out[f"y_{s}"] = y
        out[f"rec_{s}"] = np.asarray(buckets[s]["rec"])
        pos = int(y.sum())
        print(f"{s:5}  windows={len(y):6d}  AF={pos:5d} ({100 * pos / max(1, len(y)):.1f}%)  "
              f"records={len(set(buckets[s]['rec']))}")
        meta[f"{s}_windows"] = int(len(y))
        meta[f"{s}_af_windows"] = pos
        meta[f"{s}_records"] = len(set(buckets[s]["rec"]))

    # Leakage check: no record may appear in more than one split.
    sets = {s: set(buckets[s]["rec"]) for s in ("train", "val", "test")}
    for a, b in (("train", "val"), ("train", "test"), ("val", "test")):
        overlap = sets[a] & sets[b]
        assert not overlap, f"LEAKAGE: {len(overlap)} records in both {a} and {b}"
    print("\nleakage check: no record appears in more than one split - OK")

    out["meta_json"] = np.asarray(json.dumps(meta, indent=1))
    np.savez_compressed(OUT_PATH, **out)
    print(f"\nwrote {OUT_PATH} ({os.path.getsize(OUT_PATH) / 1e6:.0f} MB)")


if __name__ == "__main__":
    main()
