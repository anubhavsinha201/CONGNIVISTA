"""Generates app/test/fixtures/policy_golden_vectors.json, pinning the Kotlin
`Policy.decide()` (ticket 019, module 3) to `validate_policy.py`'s decide().

Same arrangement as the DSP and PPG fixtures: the Python is the side that can be
executed and reasoned about, the Kotlin is the deliverable, and the fixture is
what stops them drifting.

SCOPE - read before assuming this covers the whole decision.
--------------------------------------------------------------------------
`validate_policy.decide()` models the ECG-only decision: the five absolute
gates, the rules-OR-CNN irregularity test, the rate split, and the history
exception. It deliberately does NOT model the two PPG-dependent paths that
`app/lib/core/policy.dart` (and therefore Policy.kt) also has:

  * the `fusionImplausible` gate - more pulses than heartbeats, so the R-peak
    detector is wrong and the window must be refused
  * PPG corroboration escalating an irregular-but-normal-rate window to RED

Every case emitted here therefore pins `fusionImplausible = false` and leaves
both PPG inputs null, which is exactly the configuration in which the Dart,
the Kotlin and this Python agree by construction. The PPG paths are covered
separately: property-based in `validate_ppg.py`, and case-by-case against
contracts/ppg.md section 7 in PolicyGoldenVectorsTest's own PPG block.

Emitting a case that silently exercised an unmodelled path would be worse than
emitting no case at all, so `main()` asserts the scope restriction rather than
trusting this comment.

Run:  python ml/reference/generate_policy_golden_vectors.py
"""

from __future__ import annotations

import itertools
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import validate_policy as P  # noqa: E402

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "app", "test", "fixtures", "policy_golden_vectors.json",
)


def case(name, **kw):
    d = P.decide(**kw)
    return {
        "name": name,
        "inputs": {
            "sqiScore": kw.get("sqi", 0.9),
            "motionRejected": kw.get("motion", False),
            "leadOffDetected": kw.get("lead_off", False),
            "dataGapDetected": kw.get("gap", False),
            "rrIntervalCount": kw.get("rr_count", 40),
            "meanHr": kw.get("hr", 72.0),
            "rrIrregularityScore": kw.get("irregularity", 0.1),
            "cnnScore": kw.get("cnn", None),
            "sqiFailureHint": kw.get("sqi_hint", None),
            "historyIntermittent": kw.get("history_intermittent", False),
            "historyPersistent": kw.get("history_persistent", False),
        },
        "expected": {
            "tier": d.tier.name,
            "decidedBy": d.decided_by.name,
            "retakeReason": d.retake_reason.name if d.retake_reason else None,
            "retakeHint": d.retake_hint,
            "modelVersion": P.version_for(d.decided_by),
        },
    }


def build() -> list[dict]:
    cases: list[dict] = []

    # ---- 1. Every gate, and the ORDER they fire in ------------------------
    # Ordering matters as much as the gates themselves: a window that trips two
    # gates must report the earlier one, because that is the one the worker can
    # actually act on.
    cases += [
        case("gate: lead off", lead_off=True),
        case("gate: data gap", gap=True),
        case("gate: motion", motion=True),
        case("gate: poor sqi", sqi=0.49),
        case("gate: poor sqi carries its own hint", sqi=0.2,
             sqi_hint="Too much mains hum - move away from the wiring"),
        case("gate: too few beats", rr_count=29),
        case("order: lead-off beats data gap", lead_off=True, gap=True),
        case("order: data gap beats motion", gap=True, motion=True),
        case("order: motion beats poor sqi", motion=True, sqi=0.1),
        case("order: sqi beats too-few-beats", sqi=0.1, rr_count=2),
        case("order: gates beat everything downstream",
             lead_off=True, irregularity=0.99, cnn=0.99, hr=180.0),
    ]

    # ---- 2. Gate boundaries, from both sides ------------------------------
    # Every gate is a `<`, so the threshold value itself must PASS. An
    # off-by-one here is the difference between scoring a window and refusing
    # it, and no property-based check would notice.
    cases += [
        case("boundary: sqi exactly at the gate passes", sqi=0.5),
        case("boundary: sqi just under the gate fails", sqi=0.4999999),
        case("boundary: rr count exactly at the gate passes", rr_count=30),
        case("boundary: rr count one under the gate fails", rr_count=29),
        case("boundary: irregularity exactly at the gate flags", irregularity=0.5),
        case("boundary: irregularity just under does not flag", irregularity=0.4999999),
        case("boundary: cnn exactly at the threshold flags", cnn=0.1875),
        case("boundary: cnn just under does not flag", cnn=0.1874999),
        case("boundary: hr exactly at kHrLow is NOT abnormal",
             irregularity=0.9, hr=50.0),
        case("boundary: hr just under kHrLow is abnormal",
             irregularity=0.9, hr=49.999),
        case("boundary: hr exactly at kHrHigh is NOT abnormal",
             irregularity=0.9, hr=120.0),
        case("boundary: hr just over kHrHigh is abnormal",
             irregularity=0.9, hr=120.001),
    ]

    # ---- 3. The OR, and which detector gets the credit --------------------
    cases += [
        case("clean, no cnn -> GREEN by rules", irregularity=0.1),
        case("clean, cnn ran and agreed -> GREEN", irregularity=0.1, cnn=0.01),
        case("rules alone flags, no cnn", irregularity=0.9),
        case("rules alone flags, cnn ran and disagreed", irregularity=0.9, cnn=0.01),
        case("cnn alone flags", irregularity=0.1, cnn=0.9),
        case("both flag", irregularity=0.9, cnn=0.9),
        case("cnn alone flags at an abnormal rate", irregularity=0.1, cnn=0.9, hr=150.0),
    ]

    # ---- 4. The tier table, exhaustively over the axes that decide it -----
    # irregular x rate x history is the whole of rows 2-5 of contracts/tiers.md
    # section 2. Enumerated rather than sampled so a reordering of those rows
    # cannot pass.
    for irr, hr, hist in itertools.product(
        [0.1, 0.9],                      # clean / irregular
        [40.0, 72.0, 150.0],             # brady / normal / tachy
        ["none", "intermittent", "persistent"],
    ):
        cases.append(case(
            f"table: irr={irr} hr={hr} history={hist}",
            irregularity=irr, hr=hr,
            history_intermittent=(hist == "intermittent"),
            history_persistent=(hist == "persistent"),
        ))

    # ---- 5. The history exception, stated explicitly ----------------------
    # A clean window from an intermittent patient is ORANGE by history; the
    # same clean window from a persistent one is GREEN. That asymmetry is
    # deliberate and is the single most surprising line in the policy.
    cases += [
        case("clean + intermittent history -> ORANGE by history",
             irregularity=0.1, history_intermittent=True),
        case("clean + persistent history -> GREEN, no exception",
             irregularity=0.1, history_persistent=True),
        case("clean + both histories -> intermittent wins",
             irregularity=0.1, history_intermittent=True, history_persistent=True),
        case("history never reaches RED on its own",
             irregularity=0.1, hr=72.0, history_intermittent=True),
    ]

    return cases


def main() -> None:
    cases = build()

    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "duplicate case names"

    # The scope restriction this file's docstring promises, asserted rather
    # than trusted: no case may carry a PPG input or the fusion gate, because
    # validate_policy.decide() does not model either.
    for c in cases:
        assert "pulseDeficitBpm" not in c["inputs"], c["name"]
        assert "perfusedBeatFraction" not in c["inputs"], c["name"]
        assert "fusionImplausible" not in c["inputs"], c["name"]

    all_tiers = {"RETAKE", "RED", "ORANGE", "YELLOW", "GREEN"}
    tiers = {c["expected"]["tier"] for c in cases}
    assert tiers == all_tiers, (
        f"fixture does not reach every tier: missing {sorted(all_tiers - tiers)}"
    )
    reasons = {c["expected"]["retakeReason"] for c in cases} - {None}
    assert len(reasons) == 5, f"expected all 5 modelled retake reasons, got {reasons}"

    fixture = {
        "_comment": (
            "Generated by ml/reference/generate_policy_golden_vectors.py. Pins "
            "Kotlin Policy.decide() to validate_policy.py. ECG-only paths: every "
            "case has fusionImplausible=false and no PPG inputs, because "
            "validate_policy.decide() does not model those - see that script's "
            "docstring. PPG escalation is covered in PolicyGoldenVectorsTest."
        ),
        "gates": {
            "kSqiGate": P.K_SQI_GATE,
            "kMinRrIntervals": P.K_MIN_RR_INTERVALS,
            "kRrIrregularityGate": P.K_RR_IRREGULARITY_GATE,
            "kCnnThresholdInt8": P.K_CNN_THRESHOLD_INT8,
            "kHrLow": P.K_HR_LOW,
            "kHrHigh": P.K_HR_HIGH,
        },
        "cases": cases,
    }
    with open(OUT, "w") as f:
        json.dump(fixture, f, indent=1)

    print(f"wrote {len(cases)} cases to {os.path.abspath(OUT)}")
    by_tier: dict[str, int] = {}
    for c in cases:
        by_tier[c["expected"]["tier"]] = by_tier.get(c["expected"]["tier"], 0) + 1
    for t in ("RETAKE", "RED", "ORANGE", "YELLOW", "GREEN"):
        print(f"  {t:8} {by_tier.get(t, 0)}")


if __name__ == "__main__":
    main()
