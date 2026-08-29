"""Mirror of app/lib/data/record.dart and the sync state machine in
app/lib/data/sync.dart, checked against contracts/record.schema.json.

The Dart tests are the real ones. This exists for the same reason
validate_policy.py does: the data layer decides what leaves the device and what
a PHC sees, the Dart cannot be executed on every machine that touches this repo,
and a second implementation catches the class of bug where one side quietly
drifts from the contract.

It checks four things:

  1. record.schema.json is itself a valid Draft 2020-12 schema, at v4.
  2. Representative records validate against it — including the awkward ones:
     a gated RETAKE, a PPG-escalated RED, a record with no GPS fix.
  3. The upload payload carries no key outside the schema and no patient
     identifier beyond patientPseudoId. This is the mechanical enforcement of
     CLAUDE.md non-negotiable 5.
  4. The retry ladder and per-record state machine behave as contracts/sync.md
     section 5 and 7 describe.

Run:  python ml/reference/validate_record.py
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

try:
    import jsonschema
except ImportError:  # pragma: no cover
    sys.exit("pip install jsonschema")

REPO = Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO / "contracts" / "record.schema.json"

# ---- Constants: must match app/lib/data/ --------------------------------
K_SCHEMA_VERSION = 4
K_PSEUDO_ID_LENGTH = 16          # PseudoId.kLength
K_BATCH_SIZE = 25                # SyncEngine.kBatchSize
K_BACKOFF_SECONDS = [5, 30, 120, 600, 1800, 3600]   # SyncEngine.kBackoff

# Fields the device must never upload (contracts/sync.md section 3).
SERVER_OWNED = {
    "referralState", "referralUpdatedAt", "referralUpdatedBy",
    "clinicianOutcome", "clinicianOutcomeAt",
}

UUID_V4 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)

failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"  ok    {name}")
    else:
        print(f"  FAIL  {name}  {detail}")
        failures.append(name)


# ---------------------------------------------------------------------------
# Mirror of PseudoId (app/lib/data/pseudo_id.dart)
# ---------------------------------------------------------------------------

def normalise(raw: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", raw.upper())


def derive_pseudo_id(mtm_patient_id: str, deployment_salt: str) -> str:
    if not deployment_salt:
        raise ValueError("refusing to derive a pseudo-ID with an empty salt")
    n = normalise(mtm_patient_id)
    if not n:
        raise ValueError("empty after normalisation")
    mac = hmac.new(deployment_salt.encode(), n.encode(), hashlib.sha256)
    return mac.hexdigest()[:K_PSEUDO_ID_LENGTH]


# ---------------------------------------------------------------------------
# Mirror of ScreeningRecord.toJson / toSyncJson
# ---------------------------------------------------------------------------

IST = timezone(timedelta(hours=5, minutes=30))


def record(
    *,
    record_id="3f2504e0-4f89-41d3-9a0c-0305e82c3301",
    pseudo_id="a1b2c3d4e5f60718",
    whv_id="whv-021",
    phc_id="phc-042",
    captured_at=None,
    tier="GREEN",
    decided_by="rules",
    model_version="rules-1.0",
    sqi=0.82,
    **overrides,
) -> dict:
    r = {
        "recordId": record_id,
        "schemaVersion": K_SCHEMA_VERSION,
        "patientPseudoId": pseudo_id,
        "whvId": whv_id,
        "phcId": phc_id,
        "capturedAt": (captured_at or datetime(2026, 8, 29, 9, 14, 0, tzinfo=IST)).isoformat(),
        "lat": 11.0168,
        "lon": 76.9558,
        "locationAccuracyM": 12.5,
        "ageBand": "55-64",
        "villageCode": "village-042",
        "sex": None,
        "systolicBp": None,
        "diastolicBp": None,
        "glucose": None,
        "ppgResult": None,
        "ppgMeanHr": None,
        "ppgIrregularityScore": None,
        "ppgPerfusionIndex": None,
        "ecgDurationSec": 30.0,
        "sqiScore": sqi,
        "motionRejected": False,
        "leadOffDetected": False,
        "meanHr": 74.0,
        "rrIntervalCount": 37,
        "rrIrregularityScore": 0.11,
        "cnnScore": 0.02,
        "decidedBy": decided_by,
        "pulseDeficitBpm": None,
        "perfusedBeatFraction": None,
        "nonPerfusingBeats": None,
        "medianPttMs": None,
        "fusionValid": False,
        "fusionImplausible": False,
        "ppgCorroboration": "none",
        "tier": tier,
        "modelVersion": model_version,
        "ecgWaveformRef": None,
        "syncState": "pending",
        "syncedAt": None,
        "referralState": None,
        "referralUpdatedAt": None,
        "referralUpdatedBy": None,
        "clinicianOutcome": None,
        "clinicianOutcomeAt": None,
    }
    r.update(overrides)
    return r


def to_sync_json(r: dict) -> dict:
    return {k: v for k, v in r.items() if k not in SERVER_OWNED}


# ---------------------------------------------------------------------------
# Mirror of the SyncEngine per-record state machine
# ---------------------------------------------------------------------------

@dataclass
class QueuedRecord:
    record_id: str
    sync_state: str = "pending"
    attempt_count: int = 0
    next_retry_at: datetime | None = None
    synced_at: datetime | None = None
    referral_state: str | None = None


@dataclass
class FakeQueue:
    """Mirror of LocalStore, in memory."""
    rows: dict[str, QueuedRecord] = field(default_factory=dict)

    def insert(self, record_id: str) -> None:
        self.rows[record_id] = QueuedRecord(record_id)

    def next_batch(self, now: datetime, limit: int = K_BATCH_SIZE) -> list[QueuedRecord]:
        due = [
            r for r in self.rows.values()
            if r.sync_state == "pending"
            and (r.next_retry_at is None or r.next_retry_at <= now)
        ]
        return due[:limit]

    def pending_count(self) -> int:
        return sum(1 for r in self.rows.values() if r.sync_state == "pending")

    def mark_synced(self, record_id: str, at: datetime) -> None:
        r = self.rows[record_id]
        r.sync_state, r.synced_at, r.next_retry_at = "synced", at, None

    def mark_failed(self, record_id: str) -> None:
        r = self.rows[record_id]
        r.sync_state, r.attempt_count, r.next_retry_at = "failed", r.attempt_count + 1, None

    def mark_retryable(self, record_id: str, next_retry_at: datetime) -> None:
        r = self.rows[record_id]
        r.attempt_count += 1
        r.next_retry_at = next_retry_at
        r.sync_state = "pending"

    def clear_backoff(self) -> None:
        for r in self.rows.values():
            if r.sync_state == "pending":
                r.next_retry_at = None

    def apply_ack(self, record_id: str, state: str) -> None:
        r = self.rows.get(record_id)
        # Scoped to synced rows, exactly as LocalStore.applyAcks is.
        if r is not None and r.sync_state == "synced":
            r.referral_state = state


def next_retry_at(now: datetime, attempts: int, jitter: float = 1.0) -> datetime:
    base = K_BACKOFF_SECONDS[min(attempts, len(K_BACKOFF_SECONDS) - 1)]
    return now + timedelta(seconds=base * jitter)


# ---------------------------------------------------------------------------

def main() -> int:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    print(f"\ncontracts/record.schema.json  ({SCHEMA_PATH})")

    # -- 1. the schema itself ------------------------------------------------
    print("\n1. schema")
    try:
        jsonschema.Draft202012Validator.check_schema(schema)
        check("is a valid Draft 2020-12 schema", True)
    except jsonschema.SchemaError as e:
        check("is a valid Draft 2020-12 schema", False, str(e))

    check(
        "schemaVersion is pinned to the version this file mirrors",
        schema["properties"]["schemaVersion"]["const"] == K_SCHEMA_VERSION,
        f"schema says {schema['properties']['schemaVersion']['const']}, "
        f"validate_record.py says {K_SCHEMA_VERSION}",
    )
    check(
        "additionalProperties is false (this is what makes the PII guard work)",
        schema.get("additionalProperties") is False,
    )
    check(
        "server-owned referral fields exist in the schema",
        SERVER_OWNED <= set(schema["properties"]),
    )

    validator = jsonschema.Draft202012Validator(schema)

    def valid(r: dict) -> tuple[bool, str]:
        errs = sorted(validator.iter_errors(r), key=str)
        return (not errs, "; ".join(e.message for e in errs[:2]))

    # -- 2. representative records -------------------------------------------
    print("\n2. records that must validate")

    ok, why = valid(record())
    check("a plain GREEN with a GPS fix", ok, why)

    # A gated window: no scores at all. Mirrors the short-circuit in
    # EcgAnalyser.analyse.
    ok, why = valid(record(
        tier="RETAKE", decided_by="gate", sqi=0.21,
        meanHr=None, rrIntervalCount=None, rrIrregularityScore=None,
        cnnScore=None, ppgCorroboration=None,
    ))
    check("a RETAKE carrying no scores", ok, why)

    # No GPS fix. The schema says explicitly the record is still valid.
    ok, why = valid(record(lat=None, lon=None, locationAccuracyM=None))
    check("a record with no GPS fix", ok, why)

    # The case v2 exists for: a PPG-corroborated escalation that can explain
    # itself. Under v1 these keys were unrepresentable.
    escalated = record(
        tier="RED", decided_by="rules+cnn",
        model_version="af-cnn-int8-1.0+cal1",
        rrIrregularityScore=0.61, cnnScore=0.88,
        ppgResult="irregular", ppgMeanHr=88.0,
        ppgIrregularityScore=0.58, ppgPerfusionIndex=1.4,
        pulseDeficitBpm=11.2, perfusedBeatFraction=0.72,
        nonPerfusingBeats=9, medianPttMs=268.0,
        fusionValid=True, ppgCorroboration="pulseDeficit",
    )
    ok, why = valid(escalated)
    check("a PPG-escalated RED that explains itself", ok, why)

    # An acked record, as it looks on the device after a pull.
    ok, why = valid(record(
        tier="RED", syncState="synced",
        syncedAt=datetime(2026, 8, 29, 14, 2, tzinfo=IST).isoformat(),
        referralState="seen_at_phc",
        referralUpdatedAt=datetime(2026, 8, 29, 15, 30, tzinfo=IST).isoformat(),
        referralUpdatedBy="phc-042",
    ))
    check("a record carrying a PHC acknowledgement", ok, why)

    print("\n3. records that must be REJECTED")
    bad = [
        ("an unknown key (the PII guard)", record(patientName="Kavitha")),
        ("a stale schemaVersion", record(schemaVersion=2)),
        ("a non-v4 recordId", record(record_id="not-a-uuid")),
        ("a tier outside the enum", record(tier="AFIB")),
        ("a free-text referral note", record(referralNote="pt has AF")),
        ("an sqiScore above 1", record(sqi=1.4)),
        ("a pseudo-ID shorter than 8", record(pseudo_id="abc")),
        ("a referralState outside the enum", record(referralState="cured")),
        ("an ageBand outside the enum", record(ageBand="18-24")),
        ("an empty villageCode", record(villageCode="")),
        ("a systolicBp below plausible range", record(systolicBp=10)),
    ]
    for name, r in bad:
        ok, _ = valid(r)
        check(name, not ok, "validated but should not have")

    # ageBand/villageCode became required in v4. A record missing them
    # entirely (not merely null - JSON Schema treats "absent" and "null"
    # differently) must fail, since the whole point of the privacy decision
    # was that these are always present, just coarse-grained.
    missing_age = record()
    del missing_age["ageBand"]
    ok, _ = valid(missing_age)
    check("a record missing ageBand entirely", not ok, "validated but should not have")

    missing_village = record()
    del missing_village["villageCode"]
    ok, _ = valid(missing_village)
    check("a record missing villageCode entirely", not ok, "validated but should not have")

    # -- 4. the upload payload ------------------------------------------------
    print("\n4. upload payload (contracts/sync.md section 3)")

    payload = to_sync_json(escalated)
    check(
        "carries no key outside the schema",
        set(payload) <= set(schema["properties"]),
        f"extra: {set(payload) - set(schema['properties'])}",
    )
    check(
        "omits every server-owned field",
        not (set(payload) & SERVER_OWNED),
        f"leaked: {set(payload) & SERVER_OWNED}",
    )
    ok, why = valid(payload)
    check("is itself a valid v4 record", ok, why)

    # The PII check that actually matters: no value in the payload contains the
    # raw identifier the pseudo-ID was derived from.
    salt = "tn-coimbatore-2026"
    raw_id = "TN-1234 5678"
    pid = derive_pseudo_id(raw_id, salt)
    p2 = to_sync_json(record(pseudo_id=pid))
    blob = json.dumps(p2)
    check(
        "the raw MTM identifier appears nowhere in the payload",
        normalise(raw_id) not in normalise(blob),
    )
    check("the derived pseudo-ID satisfies minLength", len(pid) >= 8)

    # -- 5. pseudo-ID behaviour ----------------------------------------------
    print("\n5. pseudo-ID (app/lib/data/pseudo_id.dart)")
    check(
        "the same patient on two phones with one deployment salt collides",
        derive_pseudo_id("TN-1234 5678", salt) == derive_pseudo_id("tn12345678", salt),
    )
    check(
        "a different deployment gets a different value",
        derive_pseudo_id(raw_id, salt) != derive_pseudo_id(raw_id, "tn-salem-2026"),
    )
    check(
        "different patients do not collide",
        derive_pseudo_id("TN-1111 1111", salt) != derive_pseudo_id("TN-2222 2222", salt),
    )
    try:
        derive_pseudo_id(raw_id, "")
        check("an empty salt is refused", False, "no exception raised")
    except ValueError:
        check("an empty salt is refused", True)

    # -- 6. the sync state machine -------------------------------------------
    print("\n6. sync state machine (contracts/sync.md sections 5 and 7)")
    now = datetime(2026, 8, 29, 9, 0, tzinfo=IST)

    q = FakeQueue()
    for i in range(5):
        q.insert(f"rec-{i}")
    check("five captures queue as pending", q.pending_count() == 5)

    # Coverage appears; two records are acked before it drops again.
    q.mark_synced("rec-0", now)
    q.mark_synced("rec-1", now)
    check("a flush cut short syncs only what was acked", q.pending_count() == 3)
    check(
        "the interrupted records are still pending, not lost or failed",
        all(q.rows[f"rec-{i}"].sync_state == "pending" for i in (2, 3, 4)),
    )

    # The retry does not resend what was already accepted.
    batch = q.next_batch(now)
    check(
        "the next batch excludes already-synced records",
        {r.record_id for r in batch} == {"rec-2", "rec-3", "rec-4"},
        f"got {[r.record_id for r in batch]}",
    )

    # A record the server never mentioned must not be treated as accepted.
    q.mark_retryable("rec-2", next_retry_at(now, 0))
    check(
        "an unmentioned record stays pending",
        q.rows["rec-2"].sync_state == "pending",
    )
    check(
        "and is not due yet",
        q.next_batch(now) and "rec-2" not in {r.record_id for r in q.next_batch(now)},
    )
    check(
        "but is due after its backoff",
        "rec-2" in {r.record_id for r in q.next_batch(now + timedelta(seconds=6))},
    )

    # A rejected record leaves the queue and never comes back.
    q.mark_failed("rec-3")
    check("a rejected record leaves the queue", q.rows["rec-3"].sync_state == "failed")
    check(
        "and never reappears in a batch",
        "rec-3" not in {r.record_id for r in q.next_batch(now + timedelta(days=1))},
    )

    # The ladder.
    print("\n7. backoff ladder")
    ladder = [(next_retry_at(now, a) - now).total_seconds() for a in range(8)]
    check(
        "climbs 5s, 30s, 2m, 10m, 30m then caps at 1h",
        ladder == [5, 30, 120, 600, 1800, 3600, 3600, 3600],
        f"got {ladder}",
    )
    check(
        "jitter stays inside [0.5, 1.5] of the base",
        (next_retry_at(now, 0, 0.5) - now).total_seconds() == 2.5
        and (next_retry_at(now, 0, 1.5) - now).total_seconds() == 7.5,
    )

    # Walking back into coverage releases everything immediately.
    q.clear_backoff()
    due = {r.record_id for r in q.next_batch(now)}
    check("clearBackoff releases every pending record at once", due == {"rec-2", "rec-4"}, f"got {due}")
    check(
        "clearBackoff does not reset the ladder position",
        q.rows["rec-2"].attempt_count == 1,
    )

    # -- 8. acknowledgements --------------------------------------------------
    print("\n8. acknowledgements")
    q.apply_ack("rec-0", "seen_at_phc")
    check("an ack lands on a synced record", q.rows["rec-0"].referral_state == "seen_at_phc")
    q.apply_ack("rec-2", "acknowledged")
    check(
        "an ack cannot touch a record the server never accepted",
        q.rows["rec-2"].referral_state is None,
    )
    enum = schema["properties"]["referralState"]["enum"]
    check(
        "no referral state names a diagnosis (non-negotiable 1)",
        not any(
            w in str(v).lower()
            for v in enum if v
            for w in ("fibrillation", "af", "arrhythmia")
            if w != "af" or re.search(r"\baf\b", str(v).lower())
        ),
        f"enum: {enum}",
    )

    print()
    if failures:
        print(f"{len(failures)} FAILED: {', '.join(failures)}")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
