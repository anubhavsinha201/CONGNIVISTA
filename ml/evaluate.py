"""Performance metrics for the shipped detector, at the operating point it deploys at.

Run:  python ml/evaluate.py

Evaluates three detectors on the CinC 2017 record-disjoint test split:

    rules       RR-irregularity only (app/lib/signal/rr_features.dart)
    cnn         INT8 CNN only, at the calibrated threshold
    rules OR cnn    what contracts/tiers.md actually deploys

The third is the one that matters. Reporting only the CNN would describe a component
rather than the product.

Also reports PPV re-based to field prevalence, because the test split's ~9% AF rate is
an artefact of how CinC 2017 was assembled and is not what a WHV will encounter at a
doorstep.
"""

from __future__ import annotations

import json
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "reference"))
import dsp_reference as dsp  # noqa: E402

DATA = os.path.join(HERE, "data", "cinc2017_250hz.npz")
ARTIFACTS = os.path.join(HERE, "artifacts")

FS = 250.0
SHIPPED_SEED = 0

# From app/lib/core/policy.dart -- kept in sync by hand; both derive from
# ml/calibrate_threshold.py.
K_CNN_THRESHOLD_INT8 = 0.007812
K_RR_IRREGULARITY_GATE = 0.5

# docs/PRODUCT.md section 2: community screening in rural Gujarat found ~5.1%
# AF prevalence in people aged 50+. That, not the test split, is the population
# this instrument is pointed at.
FIELD_PREVALENCE = 0.051


def metrics(y: np.ndarray, pred: np.ndarray) -> dict:
    tp = int(((pred == 1) & (y == 1)).sum())
    fp = int(((pred == 1) & (y == 0)).sum())
    tn = int(((pred == 0) & (y == 0)).sum())
    fn = int(((pred == 0) & (y == 1)).sum())
    se = tp / (tp + fn) if tp + fn else 0.0
    sp = tn / (tn + fp) if tn + fp else 0.0
    ppv = tp / (tp + fp) if tp + fp else 0.0
    npv = tn / (tn + fn) if tn + fn else 0.0
    f1 = 2 * ppv * se / (ppv + se) if ppv + se else 0.0
    return {
        "tp": tp, "fp": fp, "tn": tn, "fn": fn,
        "sensitivity": se, "specificity": sp, "ppv": ppv, "npv": npv,
        "f1": f1, "balanced_accuracy": (se + sp) / 2,
        "accuracy": (tp + tn) / len(y),
    }


def rebase_ppv(se: float, sp: float, prevalence: float) -> tuple[float, float]:
    """PPV/NPV at a different prevalence. Se and Sp are prevalence-independent; PPV is not."""
    num = se * prevalence
    den = num + (1 - sp) * (1 - prevalence)
    ppv = num / den if den else 0.0
    num_n = sp * (1 - prevalence)
    den_n = num_n + (1 - se) * prevalence
    npv = num_n / den_n if den_n else 0.0
    return ppv, npv


def show(name: str, m: dict) -> None:
    print(f"\n{name}")
    print(f"  confusion    TP={m['tp']:4d}  FP={m['fp']:4d}  TN={m['tn']:4d}  FN={m['fn']:4d}")
    print(f"  sensitivity  {m['sensitivity']:.3f}      specificity  {m['specificity']:.3f}")
    print(f"  PPV          {m['ppv']:.3f}      NPV          {m['npv']:.3f}")
    print(f"  F1           {m['f1']:.3f}      balanced acc {m['balanced_accuracy']:.3f}")


def main() -> None:
    from sklearn.metrics import roc_auc_score, average_precision_score

    d = np.load(DATA, allow_pickle=True)
    X_te = d["X_test"]
    y = d["y_test"].astype(int)
    prevalence = y.mean()

    s = np.load(os.path.join(ARTIFACTS, f"scores_seed{SHIPPED_SEED}.npz"), allow_pickle=True)
    assert np.array_equal(s["y_true"].astype(int), y), "score file does not match the dataset"
    fp32, int8 = s["fp32"], s["int8"]

    print("=" * 72)
    print(f"ArogyaX AF detector - CinC 2017 record-disjoint test split")
    print("=" * 72)
    print(f"windows {len(y)}   AF {int(y.sum())} ({100 * prevalence:.1f}%)   "
          f"records {len(set(d['rec_test'].tolist()))}")
    print(f"shipped model: seed {SHIPPED_SEED}, full-integer INT8, "
          f"{os.path.getsize(os.path.join(ARTIFACTS, f'af_int8_seed{SHIPPED_SEED}.tflite')) / 1024:.0f} KB")

    # ---- threshold-independent -------------------------------------------
    print("\n" + "-" * 72)
    print("THRESHOLD-INDEPENDENT")
    print("-" * 72)
    print(f"  ROC-AUC   FP32 {roc_auc_score(y, fp32):.4f}   INT8 {roc_auc_score(y, int8):.4f}")
    print(f"  PR-AUC    FP32 {average_precision_score(y, fp32):.4f}   "
          f"INT8 {average_precision_score(y, int8):.4f}"
          f"      (baseline = prevalence = {prevalence:.3f})")

    # ---- rule detector ----------------------------------------------------
    print("\n" + "-" * 72)
    print("RUNNING THE RR-RULE DETECTOR OVER THE TEST SPLIT")
    print("-" * 72)
    t0 = time.time()
    rr_scores = np.zeros(len(y))
    rr_counts = np.zeros(len(y), dtype=int)
    for i in range(len(y)):
        if i % 300 == 0:
            print(f"  {i}/{len(y)} ...", flush=True)
        peaks, _, _ = dsp.detect_rpeaks(X_te[i], FS, fast=True)
        f = dsp.analyse_rr(dsp.rr_intervals_ms(peaks, FS))
        rr_scores[i] = f.irregularityScore
        rr_counts[i] = f.count
    print(f"  done in {time.time() - t0:.0f}s")

    pred_rules = (rr_scores >= K_RR_IRREGULARITY_GATE).astype(int)
    pred_cnn = (int8 >= K_CNN_THRESHOLD_INT8).astype(int)
    pred_or = ((pred_rules == 1) | (pred_cnn == 1)).astype(int)

    m_rules, m_cnn, m_or = metrics(y, pred_rules), metrics(y, pred_cnn), metrics(y, pred_or)

    print("\n" + "=" * 72)
    print("AT THE DEPLOYED OPERATING POINT")
    print("=" * 72)
    show("RULES ONLY  (rrIrregularityScore >= 0.50)", m_rules)
    show(f"CNN ONLY    (INT8 score >= {K_CNN_THRESHOLD_INT8})", m_cnn)
    show("RULES OR CNN  <- what contracts/tiers.md deploys", m_or)

    # ---- does the CNN actually add anything? ------------------------------
    caught_only_by_cnn = int(((pred_cnn == 1) & (pred_rules == 0) & (y == 1)).sum())
    caught_only_by_rules = int(((pred_rules == 1) & (pred_cnn == 0) & (y == 1)).sum())
    fp_added_by_cnn = int(((pred_cnn == 1) & (pred_rules == 0) & (y == 0)).sum())
    agreement = float((pred_cnn == pred_rules).mean())

    print("\n" + "-" * 72)
    print("IS THE ENSEMBLE ADDITIVE, OR ARE THE TWO DETECTORS REDUNDANT?")
    print("-" * 72)
    print(f"  detectors agree on            {100 * agreement:.1f}% of windows")
    print(f"  AF caught by CNN alone        {caught_only_by_cnn}")
    print(f"  AF caught by rules alone      {caught_only_by_rules}")
    print(f"  false positives added by CNN  {fp_added_by_cnn}")
    print(f"  sensitivity: rules {m_rules['sensitivity']:.3f} -> OR {m_or['sensitivity']:.3f}")
    print(f"  specificity: rules {m_rules['specificity']:.3f} -> OR {m_or['specificity']:.3f}")

    # ---- prevalence rebase ------------------------------------------------
    print("\n" + "-" * 72)
    print("WHAT THIS MEANS AT THE DOORSTEP")
    print("-" * 72)
    print("Sensitivity and specificity do not depend on prevalence. PPV does, and the")
    print("test split is not the population this is pointed at.\n")
    print(f"  {'detector':<14} {'prevalence':>11} {'PPV':>7} {'NPV':>7}")
    for nm, m in (("rules", m_rules), ("cnn", m_cnn), ("rules OR cnn", m_or)):
        for p, lbl in ((prevalence, "test 9.1%"), (FIELD_PREVALENCE, "field 5.1%")):
            ppv, npv = rebase_ppv(m["sensitivity"], m["specificity"], p)
            print(f"  {nm:<14} {lbl:>11} {ppv:>7.3f} {npv:>7.3f}")

    ppv_f, _ = rebase_ppv(m_or["sensitivity"], m_or["specificity"], FIELD_PREVALENCE)
    per_100 = m_or["sensitivity"] * FIELD_PREVALENCE * 100
    fp_100 = (1 - m_or["specificity"]) * (1 - FIELD_PREVALENCE) * 100
    print(f"\n  Per 100 people screened at 5.1% prevalence:")
    print(f"    {per_100:.1f} true AF flagged, {fp_100:.1f} false alarms, "
          f"{(1 - m_or['sensitivity']) * FIELD_PREVALENCE * 100:.1f} AF missed")
    print(f"    -> roughly 1 in {1 / ppv_f:.0f} referrals is a true case")

    # ---- latency ----------------------------------------------------------
    print("\n" + "-" * 72)
    print("INFERENCE LATENCY (desktop CPU - a budget phone will be slower)")
    print("-" * 72)
    lat = _latency(os.path.join(ARTIFACTS, f"af_int8_seed{SHIPPED_SEED}.tflite"), X_te[:50])
    print(f"  INT8 TFLite, 1 window: median {lat['median_ms']:.1f} ms  "
          f"(p95 {lat['p95_ms']:.1f} ms)")
    print(f"  Pan-Tompkins + RR features: ~{1000 * (time.time() - t0) / len(y):.0f} ms/window "
          f"in Python (Dart will differ)")

    out = {
        "split": "CinC 2017, record-disjoint",
        "windows": int(len(y)), "af_windows": int(y.sum()),
        "test_prevalence": float(prevalence), "field_prevalence": FIELD_PREVALENCE,
        "auc_fp32": float(roc_auc_score(y, fp32)), "auc_int8": float(roc_auc_score(y, int8)),
        "prauc_int8": float(average_precision_score(y, int8)),
        "rules": m_rules, "cnn": m_cnn, "rules_or_cnn": m_or,
        "af_caught_by_cnn_alone": caught_only_by_cnn,
        "af_caught_by_rules_alone": caught_only_by_rules,
        "detector_agreement": agreement,
        "latency_int8_ms": lat,
    }
    with open(os.path.join(ARTIFACTS, "evaluation.json"), "w") as fh:
        json.dump(out, fh, indent=1)
    print(f"\nwrote {os.path.join(ARTIFACTS, 'evaluation.json')}")


def _latency(tflite_path: str, xs: np.ndarray) -> dict:
    import tensorflow as tf

    interp = tf.lite.Interpreter(model_path=tflite_path, num_threads=1)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    scale, zp = inp["quantization"]

    times = []
    for x in xs:
        q = np.clip(np.round(x / scale) + zp, -128, 127).astype(np.int8)[None, :, None]
        t0 = time.perf_counter()
        interp.set_tensor(inp["index"], q)
        interp.invoke()
        interp.get_tensor(out["index"])
        times.append((time.perf_counter() - t0) * 1000)
    times = np.array(times[5:])  # drop warm-up
    return {"median_ms": float(np.median(times)), "p95_ms": float(np.percentile(times, 95))}


if __name__ == "__main__":
    main()
