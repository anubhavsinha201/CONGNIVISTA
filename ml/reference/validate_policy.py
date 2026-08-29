"""Mirror of app/lib/core/policy.dart, exercised against the same table as
app/test/policy_test.dart.

The Dart tests are the real ones. This exists because the decision layer is the
safety-critical part of the product and the Dart cannot be executed on every
machine that touches this repo, so the branching logic gets an independent run
here as well. If the two disagree, one of them has a bug.

Run:  python ml/reference/validate_policy.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from enum import Enum

# ---- Constants: must match Policy in app/lib/core/policy.dart ----
K_SQI_GATE = 0.5
K_MIN_RR_INTERVALS = 30
# Motion is inferred (ECG wander + PPG perfusion instability), not sensed by
# an IMU threshold - see ppg_reference.MOTION_WANDER_RATIO_GATE and
# MOTION_PERFUSION_INSTABILITY_GATE, and Policy in policy.dart. decide() below
# takes the already-computed bool, same as it always has, so nothing here
# actually changes behaviourally - this just removes the stale constant name.
K_RR_IRREGULARITY_GATE = 0.5
K_CNN_THRESHOLD_INT8 = None  # null until quantization_calibration.ipynb has run
K_HR_LOW = 50.0
K_HR_HIGH = 120.0
K_RULES_VERSION = "rules-1.0"
K_CNN_VERSION = "af-cnn-int8-1.0+cal1"


class Tier(Enum):
    RETAKE = "retake"
    RED = "red"
    ORANGE = "orange"
    YELLOW = "yellow"
    GREEN = "green"


class DecidedBy(Enum):
    GATE = "gate"
    RULES = "rules"
    CNN = "cnn"
    RULES_AND_CNN = "rules+cnn"
    HISTORY = "history"


class RetakeReason(Enum):
    POOR_SIGNAL_QUALITY = "poorSignalQuality"
    PATIENT_MOVED = "patientMoved"
    ELECTRODE_DETACHED = "electrodeDetached"
    DROPPED_DATA = "droppedData"
    TOO_FEW_BEATS = "tooFewBeats"


@dataclass
class Decision:
    tier: Tier
    decided_by: DecidedBy
    retake_reason: RetakeReason | None = None
    retake_hint: str | None = None


def decide(sqi=0.9, motion=False, lead_off=False, gap=False, rr_count=40,
           hr=72.0, irregularity=0.1, cnn=None, sqi_hint=None,
           history_intermittent=False, history_persistent=False) -> Decision:
    # 1. Gates, absolute and first.
    if lead_off:
        return Decision(Tier.RETAKE, DecidedBy.GATE, RetakeReason.ELECTRODE_DETACHED,
                        "Electrode detached - reattach and retake")
    if gap:
        return Decision(Tier.RETAKE, DecidedBy.GATE, RetakeReason.DROPPED_DATA,
                        "Connection dropped during capture - retake")
    if motion:
        return Decision(Tier.RETAKE, DecidedBy.GATE, RetakeReason.PATIENT_MOVED,
                        "Ask the patient to sit still, then retake")
    if sqi < K_SQI_GATE:
        return Decision(Tier.RETAKE, DecidedBy.GATE, RetakeReason.POOR_SIGNAL_QUALITY,
                        sqi_hint or "Signal unclear - reposition and retake")
    if rr_count < K_MIN_RR_INTERVALS:
        return Decision(Tier.RETAKE, DecidedBy.GATE, RetakeReason.TOO_FEW_BEATS,
                        "Not enough beats captured - record for longer")

    # 2. Irregularity from either detector.
    rules_flag = irregularity >= K_RR_IRREGULARITY_GATE
    cnn_ran = K_CNN_THRESHOLD_INT8 is not None and cnn is not None
    cnn_flag = cnn_ran and cnn >= K_CNN_THRESHOLD_INT8

    if not cnn_ran:
        by = DecidedBy.RULES
    elif rules_flag and cnn_flag:
        by = DecidedBy.RULES_AND_CNN
    elif cnn_flag:
        by = DecidedBy.CNN
    else:
        by = DecidedBy.RULES

    if not (rules_flag or cnn_flag):
        # History gets a narrow exception: intermittent means flagged on some
        # visits and clean on others, so a clean window here is not the same
        # evidence a clean window with no history at all would be. Persistent
        # does not get this exception - see contracts/tiers.md section 2.
        if history_intermittent:
            return Decision(Tier.ORANGE, DecidedBy.HISTORY)
        return Decision(Tier.GREEN, by)

    # 3. Rate decides urgency, not whether.
    rate_abnormal = hr < K_HR_LOW or hr > K_HR_HIGH
    if rate_abnormal:
        return Decision(Tier.RED, by)

    # Irregular, rate normal: ORANGE if this fits a pattern already seen
    # across visits, YELLOW if it is the first time.
    repeated = history_intermittent or history_persistent
    return Decision(Tier.ORANGE if repeated else Tier.YELLOW, by)


def version_for(by: DecidedBy) -> str:
    if by in (DecidedBy.GATE, DecidedBy.RULES, DecidedBy.HISTORY):
        return K_RULES_VERSION
    return f"{K_RULES_VERSION}+{K_CNN_VERSION}"


# --------------------------------------------------------------------------
results = []


def check(name, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    results.append((status, name, detail))
    print(f"  [{status}] {name}" + (f"  -- {detail}" if detail else ""))



def main():
    print("\nGates take precedence over any score")
    check("detached electrode",
          decide(lead_off=True, irregularity=0.99, hr=150).retake_reason
          is RetakeReason.ELECTRODE_DETACHED)
    check("dropped BLE data",
          decide(gap=True, irregularity=0.99).retake_reason is RetakeReason.DROPPED_DATA)
    check("patient moved",
          decide(motion=True, irregularity=0.99).retake_reason is RetakeReason.PATIENT_MOVED)
    check("signal quality below gate",
          decide(sqi=0.49, irregularity=0.99).retake_reason
          is RetakeReason.POOR_SIGNAL_QUALITY)
    check("too few beats",
          decide(rr_count=29, irregularity=0.99).retake_reason is RetakeReason.TOO_FEW_BEATS)
    check("gated window attributed to the gate, not a detector",
          decide(motion=True).decided_by is DecidedBy.GATE)
    check("sqi exactly at the gate passes", decide(sqi=K_SQI_GATE).tier is Tier.GREEN)
    check("minimum beat count passes",
          decide(rr_count=K_MIN_RR_INTERVALS).tier is Tier.GREEN)

    print("\nTier table (contracts/tiers.md)")
    check("regular, normal rate -> GREEN",
          decide(irregularity=0.2, hr=72).tier is Tier.GREEN)
    check("irregular, normal rate, first time -> YELLOW",
          decide(irregularity=0.8, hr=72).tier is Tier.YELLOW)
    check("irregular, tachycardic -> RED",
          decide(irregularity=0.8, hr=140).tier is Tier.RED)
    check("irregular, bradycardic -> RED",
          decide(irregularity=0.8, hr=42).tier is Tier.RED)
    check("abnormal rate alone does NOT escalate",
          decide(irregularity=0.2, hr=150).tier is Tier.GREEN)
    check("irregularity exactly at the gate escalates",
          decide(irregularity=K_RR_IRREGULARITY_GATE).tier is Tier.YELLOW)
    check("HR at the low boundary is still normal",
          decide(irregularity=0.8, hr=K_HR_LOW).tier is Tier.YELLOW)
    check("HR at the high boundary is still normal",
          decide(irregularity=0.8, hr=K_HR_HIGH).tier is Tier.YELLOW)

    print("\nFive-state triage: history (contracts/tiers.md section 2)")
    check("irregular, normal rate, repeated across visits -> ORANGE",
          decide(irregularity=0.8, hr=72, history_intermittent=True).tier
          is Tier.ORANGE)
    check("irregular, normal rate, persistent history -> ORANGE too",
          decide(irregularity=0.8, hr=72, history_persistent=True).tier
          is Tier.ORANGE)
    check("a clean visit stays GREEN with no history",
          decide(irregularity=0.1, hr=72).tier is Tier.GREEN)
    d = decide(irregularity=0.1, hr=72, history_intermittent=True)
    check("a clean visit is escalated to ORANGE by an intermittent history",
          d.tier is Tier.ORANGE and d.decided_by is DecidedBy.HISTORY)
    check("a clean visit is NOT escalated by a merely persistent history",
          decide(irregularity=0.1, hr=72, history_persistent=True).tier
          is Tier.GREEN)
    check("an abnormal rate still reaches RED regardless of history",
          decide(irregularity=0.8, hr=140, history_intermittent=True).tier
          is Tier.RED)

    print("\nDetector combination")
    check("rules alone escalate when the CNN has not shipped",
          decide(irregularity=0.8).decided_by is DecidedBy.RULES)
    check("CNN score ignored while the INT8 threshold is uncalibrated",
          decide(irregularity=0.1, cnn=0.99).tier is Tier.GREEN)
    check("version string records rules only",
          version_for(DecidedBy.RULES) == K_RULES_VERSION)
    check("version string records the CNN when it ran",
          K_CNN_VERSION in version_for(DecidedBy.RULES_AND_CNN))
    check("version string for a history-driven decision records rules only",
          version_for(DecidedBy.HISTORY) == K_RULES_VERSION)

    print("\nSafety invariants")
    forbidden = ("fibrillation", "af", "arrhythmia", "atrial")
    check("no tier name leaks a diagnosis",
          not any(w in t.value.lower() for t in Tier for w in forbidden))
    check("every retake carries an actionable hint",
          all(d.retake_hint for d in [
              decide(lead_off=True), decide(gap=True), decide(motion=True),
              decide(sqi=0.1), decide(rr_count=5)]))

    # The ordering of gates is itself load-bearing: a window can fail several at
    # once, and the worker needs the reason that tells them what to physically fix.
    check("lead-off is reported ahead of poor quality when both are true",
          decide(lead_off=True, sqi=0.0).retake_reason is RetakeReason.ELECTRODE_DETACHED)
    check("data gap is reported ahead of motion when both are true",
          decide(gap=True, motion=True).retake_reason is RetakeReason.DROPPED_DATA)

    failed = [r for r in results if r[0] == "FAIL"]
    print("\n" + "=" * 70)
    print(f"{len(results) - len(failed)}/{len(results)} checks passed")
    for _, name, detail in failed:
        print(f"  - {name}: {detail}")
    print("=" * 70)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
