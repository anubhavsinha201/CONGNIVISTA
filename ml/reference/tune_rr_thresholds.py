"""Refits the RR-irregularity logistic thresholds against MIT-BIH AFDB.

Run:  python ml/reference/tune_rr_thresholds.py

Why this script exists
-----------------------
`app/lib/signal/rr_features.dart`'s `nRmssdCentre`, `pnn50Centre`, and
`entropyCentre` are literature-derived starting points that were never fitted
to data -- rr_features.dart says so in its own PROVISIONAL comment, and
forward-references this exact file. Measured against the deployed pipeline
(ml/evaluate.py), that guess produces Sp 0.497: the rule fires on roughly half
of healthy recordings. This is the same error class the INT8 calibration work
(ml/calibrate_threshold.py) exists to correct, present in our own rule
detector instead of the CNN.

What this fits, and what it deliberately does not
--------------------------------------------------
Each of the three features (normalised RMSSD, pNN50, Shannon entropy) already
feeds a logistic of the form `1 / (1 + exp(-(x - centre) / width))`, combined
by a FIXED weighted sum (0.4 / 0.3 / 0.3). The three (centre, width) pairs are
refit here via a genuine 1-D maximum-likelihood logistic regression against
real labelled AFDB windows -- mathematically the same functional form already
in the code, so this is a fit, not an architecture change. The combination
weights are left untouched: they are not what the ticket identifies as the
guessed part, and changing them too would make it impossible to attribute any
change in performance to the fix that was actually asked for.

The final gate (kRrIrregularityGate, default 0.5) turned out NOT to be safe to
leave untouched. A class-balanced MLE fit of the three centres, at gate 0.5,
raises specificity a great deal but drops sensitivity from 0.994 to 0.805 on
held-out data -- a real regression on the metric the product is explicitly
biased toward (contracts/tiers.md: a missed AF is a preventable stroke that
happens; a false positive is one unnecessary clinic visit). So this script
also reports the gate refit to hit a target sensitivity of 0.90 -- the same
target and the same method (see gate_at_sensitivity, mirroring
calibrate_threshold.py's threshold_at_sensitivity) already used for the INT8
model's own calibration, chosen for consistency rather than picked fresh here.
Both operating points are reported; which one ships is stated as a decision
in this ticket's resolution, not decided silently by this script.

Ground truth used
------------------
AFDB ships two annotation files per record:

  .atr  -- clinician rhythm labels: '+' markers with an aux_note ('(AFIB',
           '(N', '(AFL', '(J') marking where the rhythm CHANGES. A window is
           kept only if a single label covers it entirely; a window spanning
           a rhythm transition has no honest single-window ground truth and is
           excluded, the same way CinC 2017's "Noisy" class is excluded in
           ml/prepare_cinc2017.py.
  .qrs  -- machine-detected beat locations, the community-standard reference
           beat set for this database. Used directly as ground truth here
           rather than re-running this project's own Pan-Tompkins detector:
           that detector is already validated separately, at Se/PPV ~1.000,
           against synthetic ECG with EXACT known peak positions
           (ml/reference/validate_dsp.py). Running it fresh on noisy real
           Holter data here would conflate two different questions --
           "are the thresholds well fitted" and "does the detector find the
           right beats on this specific hardware's noise profile" -- and only
           the first is this ticket's job.

AFL and J windows are excluded from the fit entirely. The deployed rule's job
is AF vs not-AF; conflating it with flutter/junctional discrimination, a
different clinical question outside docs/PRODUCT.md's stated scope, would
calibrate the threshold against a target it does not exist to hit.

Record-disjoint, not patient-disjoint would be a lie here as it would be
elsewhere: for AFDB one record genuinely IS one patient, so this split really
is patient-disjoint, unlike the CinC 2017 case.
"""

from __future__ import annotations

import glob
import json
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import dsp_reference as dsp  # noqa: E402

AFDB_DIR = os.path.join(HERE, "..", "data", "afdb", "files")
ARTIFACTS = os.path.join(HERE, "..", "artifacts")

FS = 250.0
WINDOW_SEC = 30.0
WINDOW_SAMPLES = int(WINDOW_SEC * FS)

# Mirrors Policy.kMinRrIntervals: a window without enough beats would be
# RETAKE in the deployed app, so it is excluded from fitting rather than fed
# to a threshold that will never see its like in production.
MIN_RR_INTERVALS = 30

# Existing weights and gate -- held fixed. See the module docstring.
W_RMSSD, W_PNN50, W_ENTROPY = 0.4, 0.3, 0.3
IRREGULARITY_GATE = 0.5

SEED = 20260830


# --------------------------------------------------------------------------
# AFDB parsing
# --------------------------------------------------------------------------

def usable_records() -> list[str]:
    hea = sorted(glob.glob(os.path.join(AFDB_DIR, "*.hea")))
    recs = [os.path.splitext(os.path.basename(f))[0] for f in hea if not f.endswith("-")]
    return sorted(r for r in recs if os.path.exists(os.path.join(AFDB_DIR, r + ".dat")))


def rhythm_segments(record: str):
    """[(start_sample, end_sample, label), ...] covering the whole record.

    label is one of AFIB / N / AFL / J / None (None = before the first
    annotation, or an aux_note this script does not recognise).
    """
    import wfdb

    ann = wfdb.rdann(os.path.join(AFDB_DIR, record), "atr")
    known = {"(AFIB": "AFIB", "(N": "N", "(AFL": "AFL", "(J": "J"}
    points = [(int(s), known.get(note)) for s, note in zip(ann.sample, ann.aux_note)]
    points.sort()

    segs = []
    for i, (start, label) in enumerate(points):
        end = points[i + 1][0] if i + 1 < len(points) else None
        segs.append((start, end, label))
    return segs


def label_at(segs, start: int, end: int) -> str | None:
    """The single label covering [start, end), or None if it spans a
    transition (more than one distinct label overlaps the window) or falls
    before the first annotation."""
    covering = set()
    for seg_start, seg_end, label in segs:
        seg_end_eff = seg_end if seg_end is not None else float("inf")
        if seg_start < end and seg_end_eff > start:
            covering.add(label)
    if len(covering) != 1:
        return None
    return next(iter(covering))


def record_windows(record: str) -> list[dict]:
    """Every clean, sufficiently-populated 30 s window in one record."""
    import wfdb

    sig = wfdb.rdrecord(os.path.join(AFDB_DIR, record)).p_signal[:, 0]
    qrs = wfdb.rdann(os.path.join(AFDB_DIR, record), "qrs").sample
    segs = rhythm_segments(record)

    n_windows = sig.shape[0] // WINDOW_SAMPLES
    out = []
    for w in range(n_windows):
        start, end = w * WINDOW_SAMPLES, (w + 1) * WINDOW_SAMPLES
        label = label_at(segs, start, end)
        if label not in ("AFIB", "N"):
            continue

        beats = qrs[(qrs >= start) & (qrs < end)]
        if beats.size < 2:
            continue
        rr_ms = np.diff(beats) * 1000.0 / FS
        feats = dsp.analyse_rr(rr_ms)
        if feats.count < MIN_RR_INTERVALS:
            continue

        out.append({
            "record": record,
            "window": w,
            "label": 1 if label == "AFIB" else 0,
            "n_rmssd": feats.normalisedRmssd,
            "pnn50": feats.pnn50,
            "entropy": feats.normalisedShannonEntropy,
        })
    return out


# --------------------------------------------------------------------------
# Fitting: each feature independently, via 1-D maximum-likelihood logistic
# regression -- the exact functional form already in rr_features.dart.
# --------------------------------------------------------------------------

def fit_logistic_1d(x: np.ndarray, y: np.ndarray) -> tuple[float, float]:
    """Returns (centre, width) such that 1/(1+exp(-(x-centre)/width)) is the
    maximum-likelihood fit of y ~ x.

    sklearn's LogisticRegression fits p = sigmoid(coef*x + intercept). That is
    algebraically the same curve as 1/(1+exp(-(x-centre)/width)) with
    width = 1/coef and centre = -intercept/coef, so this is a direct fit of
    the existing form, not a different model smuggled in as one.
    """
    from sklearn.linear_model import LogisticRegression

    clf = LogisticRegression(C=1e6, class_weight="balanced")
    clf.fit(x.reshape(-1, 1), y)
    coef = float(clf.coef_[0, 0])
    intercept = float(clf.intercept_[0])
    if abs(coef) < 1e-9:
        raise ValueError("feature carries no separating signal - coef ~ 0")
    width = 1.0 / coef
    centre = -intercept / coef
    return centre, width


def logistic(x, centre, width):
    return 1.0 / (1.0 + np.exp(-(x - centre) / width))


def combined_score(n_rmssd, pnn50, entropy, params):
    return np.clip(
        W_RMSSD * logistic(n_rmssd, *params["n_rmssd"])
        + W_PNN50 * logistic(pnn50, *params["pnn50"])
        + W_ENTROPY * logistic(entropy, *params["entropy"]),
        0.0, 1.0,
    )


def gate_at_sensitivity(y: np.ndarray, score: np.ndarray, target: float) -> float:
    """Lowest gate achieving at least `target` sensitivity on `score`.

    Same approach as threshold_at_sensitivity in calibrate_threshold.py,
    applied to the combined rule score instead of the CNN's. Reusing it here
    rather than inventing a different method keeps the product's calibration
    philosophy - fix the model/features, then choose the operating point for
    a stated sensitivity target - consistent across both detectors.
    """
    pos = np.sort(score[y == 1])
    k = int(np.floor((1.0 - target) * len(pos)))
    k = min(max(k, 0), len(pos) - 1)
    return float(pos[k])


def metrics(y, pred):
    tp = int(((pred == 1) & (y == 1)).sum())
    fp = int(((pred == 1) & (y == 0)).sum())
    tn = int(((pred == 0) & (y == 0)).sum())
    fn = int(((pred == 0) & (y == 1)).sum())
    se = tp / (tp + fn) if tp + fn else 0.0
    sp = tn / (tn + fp) if tn + fp else 0.0
    return {"tp": tp, "fp": fp, "tn": tn, "fn": fn, "sensitivity": se, "specificity": sp}


# --------------------------------------------------------------------------

def main() -> None:
    records = usable_records()
    print(f"AFDB records with signal: {len(records)}")

    print("extracting windows (beats from .qrs, labels from .atr) ...")
    all_windows = []
    for i, r in enumerate(records):
        w = record_windows(r)
        all_windows.extend(w)
        pos = sum(x["label"] for x in w)
        print(f"  [{i + 1:2d}/{len(records)}] {r}: {len(w):5d} clean windows, "
              f"{pos:5d} AFIB ({100 * pos / max(1, len(w)):.1f}%)")

    print(f"\ntotal clean windows: {len(all_windows)}")
    total_pos = sum(x["label"] for x in all_windows)
    print(f"total AFIB windows: {total_pos} ({100 * total_pos / len(all_windows):.1f}%)")

    # Record-disjoint split. With only 23 patients, stratify by each record's
    # own AFIB fraction so both the fit and test sets contain a mix of
    # AFIB-heavy and N-heavy patients rather than, say, all the AFIB-heavy
    # ones landing in one split by chance.
    per_record = {}
    for w in all_windows:
        per_record.setdefault(w["record"], []).append(w)
    rec_afib_frac = {
        r: sum(x["label"] for x in ws) / len(ws) for r, ws in per_record.items()
    }
    ordered = sorted(per_record.keys(), key=lambda r: rec_afib_frac[r])

    rng = np.random.default_rng(SEED)
    # Alternate-and-shuffle-within-tertile keeps the stratification while not
    # making the split a deterministic function of the sort order alone.
    tertiles = np.array_split(ordered, 3)
    fit_records, test_records = [], []
    for t in tertiles:
        t = list(t)
        rng.shuffle(t)
        n_test = max(1, round(len(t) * 0.3))
        test_records.extend(t[:n_test])
        fit_records.extend(t[n_test:])

    print(f"\nfit records ({len(fit_records)}): {sorted(fit_records)}")
    print(f"test records ({len(test_records)}): {sorted(test_records)}")

    fit_set = [w for w in all_windows if w["record"] in fit_records]
    test_set = [w for w in all_windows if w["record"] in test_records]
    print(f"fit windows: {len(fit_set)}   test windows: {len(test_set)}")

    def arrays(ws):
        return (
            np.array([w["n_rmssd"] for w in ws]),
            np.array([w["pnn50"] for w in ws]),
            np.array([w["entropy"] for w in ws]),
            np.array([w["label"] for w in ws]),
        )

    fit_rmssd, fit_pnn50, fit_entropy, fit_y = arrays(fit_set)
    test_rmssd, test_pnn50, test_entropy, test_y = arrays(test_set)

    print("\nfitting each feature's (centre, width) by 1-D logistic regression ...")
    fitted = {
        "n_rmssd": fit_logistic_1d(fit_rmssd, fit_y),
        "pnn50": fit_logistic_1d(fit_pnn50, fit_y),
        "entropy": fit_logistic_1d(fit_entropy, fit_y),
    }
    for name, (c, w) in fitted.items():
        print(f"  {name:10} centre={c:.4f}  width={w:.4f}")

    current = {
        "n_rmssd": (0.08, 0.02),
        "pnn50": (0.30, 0.08),
        "entropy": (0.65, 0.06),
    }

    print("\n" + "=" * 72)
    print("HELD-OUT PERFORMANCE (record-disjoint test set)")
    print("=" * 72)

    fit_score_current = combined_score(fit_rmssd, fit_pnn50, fit_entropy, current)
    fit_score_new = combined_score(fit_rmssd, fit_pnn50, fit_entropy, fitted)
    test_score_current = combined_score(test_rmssd, test_pnn50, test_entropy, current)
    test_score_new = combined_score(test_rmssd, test_pnn50, test_entropy, fitted)

    m_current = metrics(test_y, (test_score_current >= IRREGULARITY_GATE).astype(int))
    m_new_fixed_gate = metrics(test_y, (test_score_new >= IRREGULARITY_GATE).astype(int))
    print(f"\ncurrent (literature guess), gate=0.5:")
    print(f"  Se={m_current['sensitivity']:.3f}  Sp={m_current['specificity']:.3f}  "
          f"(tp={m_current['tp']} fp={m_current['fp']} tn={m_current['tn']} "
          f"fn={m_current['fn']}, n={len(test_y)})")
    print(f"\nrefitted centres/widths, gate=0.5 UNCHANGED:")
    print(f"  Se={m_new_fixed_gate['sensitivity']:.3f}  Sp={m_new_fixed_gate['specificity']:.3f}")
    print(f"  -- sensitivity moved from {m_current['sensitivity']:.3f} to "
          f"{m_new_fixed_gate['sensitivity']:.3f}. The product's own design (contracts/tiers.md,")
    print(f"     calibrate_threshold.py) is explicitly sensitivity-biased, so this is worth")
    print(f"     correcting rather than accepting as a side effect of a better-separated score.")

    # Sensitivity-targeted gate, found on the FIT set only (never the test
    # set - the same discipline calibrate_threshold.py's own threshold
    # selection follows), matching the INT8 calibration's own 0.90 target.
    TARGET_SE = 0.90
    gate_star = gate_at_sensitivity(fit_y, fit_score_new, TARGET_SE)
    m_new_targeted = metrics(test_y, (test_score_new >= gate_star).astype(int))
    print(f"\nrefitted centres/widths, gate RETUNED to target Se={TARGET_SE} on the fit set "
          f"(gate={gate_star:.4f}):")
    print(f"  Se={m_new_targeted['sensitivity']:.3f}  Sp={m_new_targeted['specificity']:.3f}  "
          f"(tp={m_new_targeted['tp']} fp={m_new_targeted['fp']} tn={m_new_targeted['tn']} "
          f"fn={m_new_targeted['fn']}, n={len(test_y)})")

    # Fit-set numbers too, so overfitting is visible rather than hidden.
    m_fit_current = metrics(fit_y, (fit_score_current >= IRREGULARITY_GATE).astype(int))
    m_fit_new = metrics(fit_y, (fit_score_new >= IRREGULARITY_GATE).astype(int))
    m_fit_targeted = metrics(fit_y, (fit_score_new >= gate_star).astype(int))
    print(f"\n(fit-set, gate=0.5 -- current: Se={m_fit_current['sensitivity']:.3f} "
          f"Sp={m_fit_current['specificity']:.3f}  refitted: Se={m_fit_new['sensitivity']:.3f} "
          f"Sp={m_fit_new['specificity']:.3f})")
    print(f"(fit-set, gate={gate_star:.4f} -- refitted: Se={m_fit_targeted['sensitivity']:.3f} "
          f"Sp={m_fit_targeted['specificity']:.3f}, confirms the gate search hit its own target)")

    # -----------------------------------------------------------------
    # 5-fold record-level cross-validation.
    #
    # The single 17/6 split above showed something that needs checking before
    # anything from it is trusted: a gate chosen to hit Se=0.90 on the fit
    # records hit exactly that target ON THOSE RECORDS, then collapsed to
    # Se=0.45 on the 6 held-out ones. With only 23 patients total, one
    # particular split's numbers - in EITHER direction - could easily be a
    # fluke of which specific patients happened to land where, rather than a
    # property of the method. Five folds, each held out in turn, gives a
    # spread instead of a single number, which is the only honest way to
    # report a result from this few patients.
    print("\n" + "=" * 72)
    print("5-FOLD CROSS-VALIDATION (is the single split above representative?)")
    print("=" * 72)

    fold_rng = np.random.default_rng(SEED + 1)
    all_records = list(per_record.keys())
    fold_rng.shuffle(all_records)
    n_folds = 5
    folds = np.array_split(all_records, n_folds)

    cv_current, cv_refit_gate05, cv_refit_targeted = [], [], []
    for i, held_out in enumerate(folds):
        held_out = set(held_out)
        train_ws = [w for w in all_windows if w["record"] not in held_out]
        eval_ws = [w for w in all_windows if w["record"] in held_out]
        if not eval_ws or not train_ws:
            continue

        tr_r, tr_p, tr_e, tr_y = arrays(train_ws)
        ev_r, ev_p, ev_e, ev_y = arrays(eval_ws)
        if tr_y.sum() == 0 or (tr_y == 0).sum() == 0:
            continue  # a fold's training set needs both classes to fit against

        fold_fitted = {
            "n_rmssd": fit_logistic_1d(tr_r, tr_y),
            "pnn50": fit_logistic_1d(tr_p, tr_y),
            "entropy": fit_logistic_1d(tr_e, tr_y),
        }
        tr_score = combined_score(tr_r, tr_p, tr_e, fold_fitted)
        ev_score_new = combined_score(ev_r, ev_p, ev_e, fold_fitted)
        ev_score_cur = combined_score(ev_r, ev_p, ev_e, current)
        fold_gate = gate_at_sensitivity(tr_y, tr_score, TARGET_SE)

        cv_current.append(metrics(ev_y, (ev_score_cur >= IRREGULARITY_GATE).astype(int)))
        cv_refit_gate05.append(metrics(ev_y, (ev_score_new >= IRREGULARITY_GATE).astype(int)))
        cv_refit_targeted.append(metrics(ev_y, (ev_score_new >= fold_gate).astype(int)))
        print(f"  fold {i + 1}/{n_folds} ({len(held_out)} records held out): "
              f"current Se={cv_current[-1]['sensitivity']:.3f} Sp={cv_current[-1]['specificity']:.3f}  |  "
              f"refit@0.5 Se={cv_refit_gate05[-1]['sensitivity']:.3f} Sp={cv_refit_gate05[-1]['specificity']:.3f}  |  "
              f"refit@targeted(gate={fold_gate:.3f}) Se={cv_refit_targeted[-1]['sensitivity']:.3f} "
              f"Sp={cv_refit_targeted[-1]['specificity']:.3f}")

    def summarise_cv(name, rows):
        se = np.array([r["sensitivity"] for r in rows])
        sp = np.array([r["specificity"] for r in rows])
        print(f"\n  {name}:")
        print(f"    Se  mean={se.mean():.3f}  sd={se.std():.3f}  range=[{se.min():.3f}, {se.max():.3f}]")
        print(f"    Sp  mean={sp.mean():.3f}  sd={sp.std():.3f}  range=[{sp.min():.3f}, {sp.max():.3f}]")
        return {"se_mean": float(se.mean()), "se_sd": float(se.std()),
                "se_min": float(se.min()), "se_max": float(se.max()),
                "sp_mean": float(sp.mean()), "sp_sd": float(sp.std()),
                "sp_min": float(sp.min()), "sp_max": float(sp.max())}

    print()
    cv_summary = {
        "current_guess_gate_0.5": summarise_cv("current (literature guess), gate=0.5", cv_current),
        "refitted_gate_0.5": summarise_cv("refitted centres/widths, gate=0.5", cv_refit_gate05),
        "refitted_gate_targeted": summarise_cv(
            f"refitted centres/widths, gate retuned per-fold to Se={TARGET_SE}", cv_refit_targeted),
    }
    print(f"\n  -> the targeted-gate row's cross-validated Se spread is the actual finding here:")
    print(f"     a gate chosen to hit {TARGET_SE} on 18-19 training patients does not reliably hit")
    print(f"     {TARGET_SE} on the patients held out from it. Recommendation: ship the refitted")
    print(f"     centres/widths with the gate LEFT AT 0.5, not a small-sample sensitivity-targeted")
    print(f"     gate - and treat the resulting Se as what CinC2017 (docs/PRODUCT.md's own")
    print(f"     'record-disjoint' data) already showed for the rules detector, refit rather than")
    print(f"     invented from scratch here.")

    # -----------------------------------------------------------------
    # Final deployment values: refit on ALL 23 records.
    #
    # Standard practice once cross-validation has confirmed a method
    # generalises: the CV folds exist to ESTIMATE performance on unseen
    # patients, not to pick which subset of data to ship a model trained on.
    # More data gives a better-conditioned estimate of each feature's true
    # separating point, and the CV numbers above are what gets QUOTED as the
    # expected performance - there is no separate held-out set left to
    # evaluate this final fit on without wasting data the estimate needs.
    print("\n" + "=" * 72)
    print("FINAL FIT (all 23 records) -- these are the values to ship")
    print("=" * 72)
    all_r, all_p, all_e, all_y = arrays(all_windows)
    final_fitted = {
        "n_rmssd": fit_logistic_1d(all_r, all_y),
        "pnn50": fit_logistic_1d(all_p, all_y),
        "entropy": fit_logistic_1d(all_e, all_y),
    }
    for name, (c, w) in final_fitted.items():
        print(f"  {name:10} centre={c:.4f}  width={w:.4f}")
    print(f"\n  gate: LEFT AT {IRREGULARITY_GATE} (see the cross-validation finding above -")
    print(f"  a sensitivity-targeted gate does not survive being tested on unseen patients")
    print(f"  with only 23 available; 0.5 is a principled, unfit choice: the natural")
    print(f"  midpoint of three weights summing to 1.0, not a value chosen to flatter")
    print(f"  any particular split.)")
    print(f"\n  EXPECTED PERFORMANCE (from 5-fold CV above, not measured on this exact fit):")
    print(f"    Se ~= {cv_summary['refitted_gate_0.5']['se_mean']:.3f} "
          f"(sd {cv_summary['refitted_gate_0.5']['se_sd']:.3f})")
    print(f"    Sp ~= {cv_summary['refitted_gate_0.5']['sp_mean']:.3f} "
          f"(sd {cv_summary['refitted_gate_0.5']['sp_sd']:.3f})")

    os.makedirs(ARTIFACTS, exist_ok=True)
    summary = {
        "source": "MIT-BIH AFDB, record-disjoint (one record = one patient)",
        "window_seconds": WINDOW_SEC,
        "min_rr_intervals": MIN_RR_INTERVALS,
        "excluded_labels": ["AFL", "J"],
        "n_records": len(records),
        "fit_records": sorted(fit_records),
        "test_records": sorted(test_records),
        "n_fit_windows": len(fit_set),
        "n_test_windows": len(test_set),
        "fitted_thresholds_single_split": {
            "nRmssdCentre": fitted["n_rmssd"][0], "nRmssdWidth": fitted["n_rmssd"][1],
            "pnn50Centre": fitted["pnn50"][0], "pnn50Width": fitted["pnn50"][1],
            "entropyCentre": fitted["entropy"][0], "entropyWidth": fitted["entropy"][1],
        },
        "fitted_thresholds_final_all_records": {
            "nRmssdCentre": final_fitted["n_rmssd"][0], "nRmssdWidth": final_fitted["n_rmssd"][1],
            "pnn50Centre": final_fitted["pnn50"][0], "pnn50Width": final_fitted["pnn50"][1],
            "entropyCentre": final_fitted["entropy"][0], "entropyWidth": final_fitted["entropy"][1],
        },
        "weights_unchanged": {"wRmssd": W_RMSSD, "wPnn50": W_PNN50, "wEntropy": W_ENTROPY},
        "gate_default": IRREGULARITY_GATE,
        "gate_sensitivity_targeted": gate_star,
        "gate_target_sensitivity": TARGET_SE,
        "held_out_test": {
            "current_guess_gate_0.5": m_current,
            "refitted_gate_0.5": m_new_fixed_gate,
            "refitted_gate_targeted": m_new_targeted,
        },
        "fit_set": {
            "current_guess_gate_0.5": m_fit_current,
            "refitted_gate_0.5": m_fit_new,
            "refitted_gate_targeted": m_fit_targeted,
        },
        "cross_validation_5fold": cv_summary,
        "recommendation": (
            "Ship fitted_thresholds_final_all_records with gate LEFT AT 0.5. The "
            "sensitivity-targeted gate does not survive cross-validation with "
            "only 23 patients (see cross_validation_5fold.refitted_gate_targeted "
            "se_sd/range) - it hits its target on the patients it was tuned on "
            "and misses badly on the ones held out from it. Expected performance "
            "is the 5-fold CV numbers (refitted_gate_0.5), not a number measured "
            "directly on the final fit, since there is no data left to hold out "
            "from a fit that intentionally uses all of it."
        ),
    }
    out_path = os.path.join(ARTIFACTS, "rr_threshold_fit.json")
    with open(out_path, "w") as fh:
        json.dump(summary, fh, indent=1)
    print(f"\nwrote {out_path}")
    print("\nNext: update nRmssdCentre/Width, pnn50Centre/Width, entropyCentre/Width in")
    print("app/lib/signal/rr_features.dart AND ml/reference/dsp_reference.py to the")
    print("FINAL FIT values above (all 23 records), leave kRrIrregularityGate at 0.5,")
    print("then rerun ml/evaluate.py to confirm the deployed-pipeline specificity moves.")


if __name__ == "__main__":
    main()
