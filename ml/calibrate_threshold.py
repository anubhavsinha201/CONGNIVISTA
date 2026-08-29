"""Measure the FP32 -> INT8 score shift and refit the decision threshold.

Run:  python ml/calibrate_threshold.py

This is the experiment behind docs/PRODUCT.md section 6. Reads every
ml/artifacts/scores_seed*.npz produced by train_af_cnn.py and answers one question:

    If you pick the operating threshold on the full-precision model and then quantise
    -- which is the standard workflow -- what sensitivity does the DEPLOYED model
    actually have?

Outputs ml/artifacts/calibration.png and calibration_summary.json.

TWO THINGS THIS SCRIPT IS CAREFUL ABOUT
---------------------------------------

1. The shipped threshold is model-specific. A threshold refitted on seed 3's INT8
   scores is meaningless for seed 0's model. So exactly ONE seed is nominated as the
   shipped model and its threshold goes into Policy.kCnnThresholdInt8. The
   multi-seed analysis exists to characterise the SHIFT, not to average thresholds
   together -- averaging them would be nonsense.

2. The claim is unpredictability, not magnitude. If the mean drop is small but varies
   a lot between seeds, that supports the argument rather than undermining it: you
   cannot know your deployed operating point in advance, therefore you must measure
   it. Report whatever is measured. Do not go looking for a configuration that makes
   the chart more dramatic -- that is precisely the methodological failure section 6
   accuses the field of.
"""

from __future__ import annotations

import glob
import json
import os

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ARTIFACTS = os.path.join(HERE, "artifacts")

TARGET_SENSITIVITY = 0.90
SHIPPED_SEED = 0


def threshold_at_sensitivity(y: np.ndarray, s: np.ndarray, target: float) -> float:
    """Lowest threshold achieving at least `target` sensitivity."""
    pos = np.sort(s[y == 1])
    k = int(np.floor((1.0 - target) * len(pos)))
    k = min(max(k, 0), len(pos) - 1)
    return float(pos[k])


def operating_point(y: np.ndarray, s: np.ndarray, t: float) -> tuple[float, float]:
    pos, neg = s[y == 1], s[y == 0]
    sens = float((pos >= t).mean())
    spec = float((neg < t).mean())
    return sens, spec


def main() -> None:
    files = sorted(glob.glob(os.path.join(ARTIFACTS, "scores_seed*.npz")))
    if not files:
        raise SystemExit("No score files. Run ml/train_af_cnn.py --seed N first.")

    from sklearn.metrics import roc_auc_score

    rows = []
    for path in files:
        d = np.load(path, allow_pickle=True)
        y, fp32, int8 = d["y_true"], d["fp32"], d["int8"]
        seed = int(d["seed"])

        # The standard workflow: choose the operating point on the FP32 model.
        t_fp32 = threshold_at_sensitivity(y, fp32, TARGET_SENSITIVITY)
        sens_fp32, spec_fp32 = operating_point(y, fp32, t_fp32)

        # ...then quantise and deploy, carrying that threshold over unchanged.
        sens_naive, spec_naive = operating_point(y, int8, t_fp32)

        # What we do instead: refit on the quantised model's own scores.
        t_int8 = threshold_at_sensitivity(y, int8, TARGET_SENSITIVITY)
        sens_refit, spec_refit = operating_point(y, int8, t_int8)

        # Quantisation does not only SHIFT the scores -- it collapses how many
        # operating points exist at all. The INT8 output is one int8 value, so the
        # whole test set lands on a few dozen distinct scores instead of one per
        # sample. In the clinically relevant sensitivity band there may be only a
        # dozen or so reachable thresholds, and the target may simply not be one of
        # them. You cannot fine-tune the operating point of a deployed INT8 model;
        # you pick the nearest available rung.
        uniq_int8 = np.unique(int8)
        step = float(np.min(np.diff(uniq_int8))) if uniq_int8.size > 1 else float("nan")
        reachable = sorted({round(float((int8[y == 1] >= t).mean()), 6) for t in uniq_int8})
        in_band = [s for s in reachable if 0.80 <= s <= 0.98]
        nearest = min(in_band, key=lambda s: abs(s - TARGET_SENSITIVITY)) if in_band else float("nan")

        rows.append({
            "n_distinct_scores_fp32": int(np.unique(fp32).size),
            "n_distinct_scores_int8": int(uniq_int8.size),
            "int8_score_step": step,
            "reachable_operating_points_in_band": len(in_band),
            "nearest_reachable_sensitivity": nearest,
            "seed": seed,
            "auc_fp32": float(roc_auc_score(y, fp32)),
            "auc_int8": float(roc_auc_score(y, int8)),
            "threshold_fp32": t_fp32,
            "threshold_int8_refit": t_int8,
            "sens_fp32": sens_fp32, "spec_fp32": spec_fp32,
            "sens_int8_naive": sens_naive, "spec_int8_naive": spec_naive,
            "sens_int8_refit": sens_refit, "spec_int8_refit": spec_refit,
            "sensitivity_lost": sens_fp32 - sens_naive,
            "specificity_cost_of_refit": spec_naive - spec_refit,
        })

    drops = np.array([r["sensitivity_lost"] for r in rows])

    print(f"\ntarget sensitivity: {TARGET_SENSITIVITY:.2f}   seeds: {len(rows)}\n")
    hdr = f"{'seed':>5} {'AUC fp32':>9} {'AUC int8':>9} {'Se fp32':>8} {'Se int8':>8} {'Se lost':>8} {'Se refit':>9}"
    print(hdr)
    print("-" * len(hdr))
    for r in rows:
        print(f"{r['seed']:>5} {r['auc_fp32']:>9.4f} {r['auc_int8']:>9.4f} "
              f"{r['sens_fp32']:>8.3f} {r['sens_int8_naive']:>8.3f} "
              f"{r['sensitivity_lost']:>8.3f} {r['sens_int8_refit']:>9.3f}")

    print(f"\nsensitivity lost by carrying the FP32 threshold over:")
    print(f"  mean {drops.mean():+.4f}   sd {drops.std():.4f}   "
          f"range [{drops.min():+.4f}, {drops.max():+.4f}]")

    pts = np.array([r["reachable_operating_points_in_band"] for r in rows])
    n_i8 = np.array([r["n_distinct_scores_int8"] for r in rows])
    n_fp = np.array([r["n_distinct_scores_fp32"] for r in rows])
    print("\noperating-point granularity (the part nobody measures):")
    print(f"  distinct scores on the test set:  FP32 {n_fp.mean():.0f}   "
          f"INT8 {n_i8.min()}-{n_i8.max()}")
    print(f"  INT8 score step: {rows[0]['int8_score_step']:.6f} "
          f"(1/{round(1 / rows[0]['int8_score_step'])})")
    print(f"  reachable operating points with Se in [0.80, 0.98]: "
          f"{pts.min()}-{pts.max()}")
    print("  -> the deployed model's operating point cannot be tuned freely;")
    print("     you take the nearest available rung, which may miss the target.")

    shipped = next((r for r in rows if r["seed"] == SHIPPED_SEED), rows[0])
    print(f"\nSHIPPED MODEL: seed {shipped['seed']}")
    print(f"  Policy.kCnnThresholdInt8 = {shipped['threshold_int8_refit']:.6f}")
    print(f"  refitted: Se={shipped['sens_int8_refit']:.3f} Sp={shipped['spec_int8_refit']:.3f}")
    print(f"  naive:    Se={shipped['sens_int8_naive']:.3f} Sp={shipped['spec_int8_naive']:.3f}")

    summary = {
        "target_sensitivity": TARGET_SENSITIVITY,
        "n_seeds": len(rows),
        "sensitivity_lost_mean": float(drops.mean()),
        "sensitivity_lost_sd": float(drops.std()),
        "sensitivity_lost_min": float(drops.min()),
        "sensitivity_lost_max": float(drops.max()),
        "int8_score_step": rows[0]["int8_score_step"],
        "reachable_operating_points_min": int(pts.min()),
        "reachable_operating_points_max": int(pts.max()),
        "shipped_seed": shipped["seed"],
        "kCnnThresholdInt8": shipped["threshold_int8_refit"],
        "split": "record-disjoint (CinC 2017 publishes no subject IDs)",
        "per_seed": rows,
    }
    with open(os.path.join(ARTIFACTS, "calibration_summary.json"), "w") as fh:
        json.dump(summary, fh, indent=1)

    _plot(files, rows, drops)
    print(f"\nwrote {os.path.join(ARTIFACTS, 'calibration.png')}")
    print(f"wrote {os.path.join(ARTIFACTS, 'calibration_summary.json')}")


def _plot(files, rows, drops) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    d = np.load(files[0], allow_pickle=True)
    y, fp32, int8 = d["y_true"], d["fp32"], d["int8"]
    r0 = rows[0]

    fig, ax = plt.subplots(1, 3, figsize=(16.5, 4.8))

    FP_C, I8_C, REFIT_C = "#4C72B0", "#C44E52", "#55A868"

    # --- Panel 1: sensitivity as a function of threshold ------------------
    # A histogram of scores hides the finding, because both thresholds sit
    # against the left edge of a 0-1 axis and the two panels look identical.
    # Sensitivity-vs-threshold shows both effects at once: the curves separate
    # (the shift), and the INT8 trace is a staircase rather than a curve
    # (the collapse in reachable operating points).
    grid = np.linspace(0, 1, 2000)
    pos_fp, pos_i8 = fp32[y == 1], int8[y == 1]
    se_fp = [(pos_fp >= t).mean() for t in grid]
    se_i8 = [(pos_i8 >= t).mean() for t in grid]

    ax[0].plot(grid, se_fp, color=FP_C, lw=2, label="FP32 (continuous)")
    ax[0].plot(grid, se_i8, color=I8_C, lw=2, label="INT8 (discrete steps)")
    ax[0].axhline(0.90, color="k", ls="--", lw=1.2, label="target Se = 0.90")
    ax[0].axvline(r0["threshold_fp32"], color="k", ls=":", lw=1.2)
    ax[0].set_xlim(0, 0.10)
    ax[0].set_ylim(0.80, 1.005)
    ax[0].set_xlabel("threshold")
    ax[0].set_ylabel("sensitivity")
    ax[0].set_title("Sensitivity vs threshold (seed %d)" % r0["seed"])
    ax[0].legend(fontsize=8, loc="lower left")

    # --- Panel 2: which operating points exist at all ---------------------
    neg_fp, neg_i8 = fp32[y == 0], int8[y == 0]
    roc_fp = [(1 - (neg_fp < t).mean(), (pos_fp >= t).mean()) for t in np.unique(fp32)]
    roc_i8 = [(1 - (neg_i8 < t).mean(), (pos_i8 >= t).mean()) for t in np.unique(int8)]
    roc_fp = np.array(sorted(roc_fp))
    roc_i8 = np.array(sorted(roc_i8))

    ax[1].plot(roc_fp[:, 0], roc_fp[:, 1], color=FP_C, lw=2,
               label=f"FP32 — {r0['n_distinct_scores_fp32']} distinct scores")
    ax[1].plot(roc_i8[:, 0], roc_i8[:, 1], "o", color=I8_C, ms=5, alpha=0.85,
               label=f"INT8 — only {r0['n_distinct_scores_int8']} reachable points")
    ax[1].axhline(0.90, color="k", ls="--", lw=1.2, label="target Se = 0.90")
    ax[1].set_xlim(0, 0.6)
    ax[1].set_ylim(0.80, 1.005)
    ax[1].set_xlabel("1 - specificity")
    ax[1].set_ylabel("sensitivity")
    ax[1].set_title("Every operating point available on the deployed model")
    ax[1].legend(fontsize=8, loc="lower right")

    seeds = [r["seed"] for r in rows]
    ax[2].axhline(0.90, color="k", ls="--", lw=1.2, label="target Se = 0.90")
    ax[2].plot(seeds, [r["sens_int8_naive"] for r in rows], "o-", color="#C44E52",
               label="INT8, FP32 threshold carried over")
    ax[2].plot(seeds, [r["sens_int8_refit"] for r in rows], "s-", color="#55A868",
               label="INT8, threshold refitted")
    ax[2].set_xlabel("seed")
    ax[2].set_ylabel("sensitivity")
    r_ship = next((r for r in rows if r["seed"] == SHIPPED_SEED), rows[0])
    ax[2].set_xticks(seeds)
    ax[2].set_title(
        f"Se lost: {drops.mean():.3f} +/- {drops.std():.3f}   |   "
        f"only {r_ship['reachable_operating_points_in_band']} operating points exist")
    ax[2].legend(fontsize=8)

    fig.suptitle(
        "Quantisation does not just shift the operating point - it collapses which "
        "operating points exist. The threshold must be refitted on INT8 scores.",
        fontsize=11.5)
    fig.tight_layout()
    fig.savefig(os.path.join(ARTIFACTS, "calibration.png"), dpi=150)


if __name__ == "__main__":
    main()
