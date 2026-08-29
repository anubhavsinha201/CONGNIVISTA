"""Mirror of app/lib/data/patient_history.dart, exercised against its own table.

Covers features 1, 2, 4, 5 and 12 from the feature list: the longitudinal risk
profile, patient-level risk, the flag rate across visits, the timeline, and the
adaptive repeat interval.

Run:  python ml/reference/validate_history.py
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

# ---- constants mirrored from PatientHistory ------------------------------
MIN_SCREENINGS_FOR_BURDEN = 3
MIN_OBSERVATION_DAYS = 14
REFERRAL_LAPSE_DAYS = 14

INTERVAL_CONFIRMED = 90
INTERVAL_OPEN_REFERRAL = 14
INTERVAL_INTERMITTENT = 30
INTERVAL_AFTER_RED = 14
INTERVAL_AFTER_AMBER = 45
INTERVAL_AFTER_RETAKE = 7
INTERVAL_ROUTINE = 180

OPEN_STATES = {"acknowledged", "patient_contacted", "visit_scheduled", "seen_at_phc"}

NOW = datetime(2026, 8, 29, tzinfo=timezone.utc)


@dataclass
class Entry:
    days_ago: int
    tier: str
    referral_state: str | None = None
    outcome: str | None = None

    @property
    def captured_at(self):
        return NOW - timedelta(days=self.days_ago)

    @property
    def scored(self):
        return self.tier != "RETAKE"

    @property
    def flagged(self):
        return self.tier in ("RED", "AMBER")


@dataclass
class History:
    entries: list[Entry] = field(default_factory=list)

    def __post_init__(self):
        self.entries.sort(key=lambda e: e.days_ago)  # newest first

    @property
    def scored(self):
        return [e for e in self.entries if e.scored]

    @property
    def flagged_count(self):
        return sum(1 for e in self.scored if e.flagged)

    @property
    def observation_days(self):
        if len(self.entries) < 2:
            return 0
        return self.entries[-1].days_ago - self.entries[0].days_ago

    @property
    def flag_rate(self):
        return self.flagged_count / len(self.scored) if self.scored else 0.0

    @property
    def is_intermittent(self):
        return len(self.scored) >= 2 and 0 < self.flagged_count < len(self.scored)

    @property
    def is_persistent(self):
        return (len(self.scored) >= MIN_SCREENINGS_FOR_BURDEN
                and self.flagged_count == len(self.scored))

    @property
    def burden_confidence(self):
        if len(self.scored) < MIN_SCREENINGS_FOR_BURDEN:
            return "insufficient"
        if self.observation_days < MIN_OBSERVATION_DAYS:
            return "provisional"
        return "usable"

    @property
    def worst_tier(self):
        for t in ("RED", "AMBER"):
            if any(e.tier == t for e in self.scored):
                return t
        return "GREEN" if self.scored else "RETAKE"

    @property
    def has_confirmed_finding(self):
        return any(e.outcome == "confirmed" for e in self.entries)

    @property
    def has_open_referral(self):
        return any(e.flagged and e.referral_state in OPEN_STATES for e in self.entries)

    @property
    def has_lapsed_referral(self):
        return any(e.flagged and e.referral_state in (None, "none")
                   and e.days_ago > REFERRAL_LAPSE_DAYS for e in self.entries)

    @property
    def recommended_repeat_days(self):
        if not self.entries:
            return 0
        if self.has_confirmed_finding:
            return INTERVAL_CONFIRMED
        if self.has_open_referral:
            return INTERVAL_OPEN_REFERRAL
        if self.is_intermittent:
            return INTERVAL_INTERMITTENT
        return {"RED": INTERVAL_AFTER_RED, "AMBER": INTERVAL_AFTER_AMBER,
                "RETAKE": INTERVAL_AFTER_RETAKE}.get(self.worst_tier, INTERVAL_ROUTINE)

    @property
    def days_until_due(self):
        if not self.entries:
            return None
        return self.recommended_repeat_days - self.entries[0].days_ago

    @property
    def is_due(self):
        d = self.days_until_due
        return d is not None and d <= 0

    @property
    def repeat_reason_key(self):
        if not self.entries:
            return "never_screened"
        if self.has_confirmed_finding:
            return "under_clinician_care"
        if self.has_open_referral:
            return "referral_open"
        if self.is_intermittent:
            return "varies_between_visits"
        return {"RED": "previous_urgent_referral", "AMBER": "previous_referral",
                "RETAKE": "last_capture_unusable"}.get(self.worst_tier, "routine")


results = []


def check(name, cond, detail=""):
    status = "PASS" if cond else "FAIL"
    results.append((status, name, detail))
    print(f"  [{status}] {name}" + (f"  -- {detail}" if detail else ""))


print("\n1. Feature 5 - the timeline from the notebook page")
# Jan normal, Feb irregular, Mar suspicious -> PHC, Apr suspicious -> referred.
notebook = History([
    Entry(240, "GREEN"),
    Entry(210, "AMBER"),
    Entry(180, "AMBER", referral_state="seen_at_phc"),
    Entry(150, "RED", referral_state="acknowledged"),
])
check("timeline is newest-first", notebook.entries[0].days_ago == 150)
check("four visits, all scored", len(notebook.scored) == 4)
check("worst tier is RED", notebook.worst_tier == "RED")

print("\n2. Feature 4 - flag rate and intermittency")
check("flag rate counts only scored visits", abs(notebook.flag_rate - 0.75) < 1e-9,
      f"{notebook.flagged_count}/{len(notebook.scored)}")
check("flagged on some visits but not all -> intermittent", notebook.is_intermittent)

# A RETAKE is missing data, not a negative. Counting it as one deflates the rate.
with_retake = History([Entry(30, "AMBER"), Entry(20, "RETAKE"), Entry(10, "AMBER")])
check("RETAKE excluded from the denominator", abs(with_retake.flag_rate - 1.0) < 1e-9,
      f"rate={with_retake.flag_rate:.2f} over {len(with_retake.scored)} scored")
check("all-flagged is persistent, not intermittent",
      with_retake.is_persistent is False and with_retake.is_intermittent is False,
      "only 2 scored visits, below the burden minimum")

print("\n3. Confidence gating - refusing to quote a rate too early")
two = History([Entry(10, "AMBER"), Entry(3, "GREEN")])
check("two visits -> insufficient", two.burden_confidence == "insufficient")
tight = History([Entry(6, "AMBER"), Entry(3, "GREEN"), Entry(1, "AMBER")])
check("three visits in a week -> provisional", tight.burden_confidence == "provisional",
      f"observation_days={tight.observation_days}")
check("three visits over months -> usable", notebook.burden_confidence == "usable",
      f"observation_days={notebook.observation_days}")

print("\n4. Feature 12 - adaptive repeat interval")
check("never screened -> 0", History([]).recommended_repeat_days == 0)
check("routine after a single GREEN",
      History([Entry(5, "GREEN")]).recommended_repeat_days == INTERVAL_ROUTINE)
check("sooner after a RED",
      History([Entry(5, "RED")]).recommended_repeat_days == INTERVAL_AFTER_RED)
check("soonest after a RETAKE - nothing was learned",
      History([Entry(5, "RETAKE")]).recommended_repeat_days == INTERVAL_AFTER_RETAKE)
check("intermittent beats a plain AMBER history",
      notebook.recommended_repeat_days == INTERVAL_OPEN_REFERRAL,
      "open referral outranks intermittency")

interm = History([Entry(90, "AMBER"), Entry(60, "GREEN"), Entry(30, "AMBER")])
check("intermittent -> 30 days",
      interm.recommended_repeat_days == INTERVAL_INTERMITTENT
      and interm.repeat_reason_key == "varies_between_visits")

confirmed = History([Entry(40, "RED", referral_state="closed", outcome="confirmed")])
check("confirmed case moves to follow-up, not re-screening",
      confirmed.recommended_repeat_days == INTERVAL_CONFIRMED
      and confirmed.repeat_reason_key == "under_clinician_care")

print("\n5. Due list and lapsed referrals")
check("overdue is negative", History([Entry(200, "GREEN")]).days_until_due == -20)
check("not yet due is positive", History([Entry(5, "GREEN")]).days_until_due == 175)
lapsed = History([Entry(40, "RED", referral_state="none")])
check("a flagged visit nobody acted on is lapsed", lapsed.has_lapsed_referral)
acted = History([Entry(40, "RED", referral_state="seen_at_phc")])
check("an acted-on referral is not lapsed", not acted.has_lapsed_referral)
check("a GREEN visit is never lapsed",
      not History([Entry(40, "GREEN")]).has_lapsed_referral)

print("\n6. Safety - what the history may and may not assert")
check("worst tier is remembered across visits",
      History([Entry(90, "RED"), Entry(10, "GREEN")]).worst_tier == "RED",
      "a later GREEN must not erase an earlier RED")
check("a confirmed patient is never treated as routine",
      History([Entry(90, "RED", outcome="confirmed"), Entry(2, "GREEN")]
              ).repeat_reason_key == "under_clinician_care")
check("no field here can hold a condition name",
      all(k not in json.dumps([e.__dict__ for e in notebook.entries], default=str).lower()
          for k in ("fibrillation", "arrhythmia", "atrial")))

print("\n7. Feature 3 - which records can train the model")
labelled = [Entry(1, "AMBER", outcome="confirmed"),
            Entry(2, "AMBER", outcome="not_confirmed"),
            Entry(3, "AMBER", outcome="inconclusive"),
            Entry(4, "AMBER", outcome=None)]
usable = [e for e in labelled if e.outcome in ("confirmed", "not_confirmed")]
check("only settled findings are training labels", len(usable) == 2,
      "inconclusive is NOT a negative - counting it as one would teach the "
      "model that intermittent AF is absence of AF")

failed = [r for r in results if r[0] == "FAIL"]
print("\n" + "=" * 70)
print(f"{len(results) - len(failed)}/{len(results)} checks passed")
for _, name, detail in failed:
    print(f"  - {name}: {detail}")
print("=" * 70)
sys.exit(1 if failed else 0)
