import assert from 'node:assert/strict';
import { test, describe, beforeEach } from 'node:test';

import { MemoryRepo } from '../src/memory_repo.js';
import { SyncService } from '../src/service.js';
import { REFERRAL_STATES, RECORD_SCHEMA } from '../src/validate.js';

const DEVICE = { whvId: 'whv-021', phcId: 'phc-042' };

let n = 0;
function uuid() {
  // Deterministic v4-shaped ids, so a failure names the same record twice.
  const h = (n++).toString(16).padStart(12, '0');
  return `3f2504e0-4f89-41d3-9a0c-${h}`;
}

function record(overrides = {}) {
  return {
    recordId: uuid(),
    schemaVersion: 2,
    patientPseudoId: 'a1b2c3d4e5f60718',
    whvId: 'whv-021',
    phcId: 'phc-042',
    capturedAt: '2026-08-29T09:14:00+05:30',
    lat: 11.0168,
    lon: 76.9558,
    locationAccuracyM: 12.5,
    ecgDurationSec: 30,
    sqiScore: 0.82,
    motionRejected: false,
    leadOffDetected: false,
    meanHr: 74,
    rrIntervalCount: 37,
    rrIrregularityScore: 0.11,
    cnnScore: 0.02,
    decidedBy: 'rules',
    fusionValid: false,
    fusionImplausible: false,
    ppgCorroboration: 'none',
    tier: 'GREEN',
    modelVersion: 'rules-1.0',
    syncState: 'pending',
    ...overrides,
  };
}

let repo;
let service;

beforeEach(() => {
  repo = new MemoryRepo();
  repo.addDevice({ token: 'tok', ...DEVICE });
  service = new SyncService(repo);
});

describe('ingest', () => {
  test('accepts a well-formed record and stores it', async () => {
    const r = record();
    const results = await service.ingest([r], DEVICE);

    assert.deepEqual(results, [{ recordId: r.recordId, status: 'accepted' }]);
    assert.equal(repo.records.size, 1);
  });

  test('returns one result per submitted record, in order', async () => {
    const rs = [record(), record({ tier: 'NOPE' }), record()];
    const results = await service.ingest(rs, DEVICE);

    assert.equal(results.length, 3);
    assert.deepEqual(
      results.map((x) => x.recordId),
      rs.map((x) => x.recordId),
    );
    assert.deepEqual(
      results.map((x) => x.status),
      ['accepted', 'rejected', 'accepted'],
    );
  });

  test('a schema-invalid record is rejected, not retried forever', async () => {
    const [res] = await service.ingest([record({ sqiScore: 1.4 })], DEVICE);
    assert.equal(res.status, 'rejected');
    assert.equal(res.code, 'schema_invalid');
  });

  test('an unknown key is rejected — the PII guard', async () => {
    const [res] = await service.ingest(
      [record({ patientName: 'Kavitha' })],
      DEVICE,
    );
    assert.equal(res.status, 'rejected');
    assert.equal(res.code, 'schema_invalid');
    assert.equal(repo.records.size, 0);
  });

  test('a v1 record is rejected — schemaVersion is pinned', async () => {
    const [res] = await service.ingest([record({ schemaVersion: 1 })], DEVICE);
    assert.equal(res.status, 'rejected');
  });

  test('one bad record does not stop the good ones in the same batch', async () => {
    const good1 = record();
    const good2 = record();
    await service.ingest([good1, record({ tier: 'AFIB' }), good2], DEVICE);

    assert.equal(repo.records.size, 2);
    assert.ok(repo.records.has(good1.recordId));
    assert.ok(repo.records.has(good2.recordId));
  });

  test('identity is taken from the token, never the payload', async () => {
    const r = record({ whvId: 'whv-999', phcId: 'phc-999' });
    await service.ingest([r], DEVICE);

    const stored = repo.records.get(r.recordId);
    assert.equal(stored.whvId, 'whv-021');
    assert.equal(stored.phcId, 'phc-042');
  });

  test('device bookkeeping is not stored server-side', async () => {
    const r = record({ syncState: 'pending', syncedAt: null });
    await service.ingest([r], DEVICE);

    const stored = repo.records.get(r.recordId);
    assert.ok(!('syncState' in stored));
    assert.ok(!('syncedAt' in stored));
  });

  test('a database outage downgrades acceptance to retryable', async () => {
    // The failure mode that matters most: never tell a device "synced" for a
    // record that was not written. That loses a referral silently.
    repo.failNextWrite = true;
    const [res] = await service.ingest([record()], DEVICE);

    assert.equal(res.status, 'retryable');
    assert.equal(res.code, 'db_unavailable');
    assert.equal(repo.records.size, 0);
  });
});

describe('idempotency — the property retry safety rests on', () => {
  test('re-uploading the same record creates no duplicate', async () => {
    const r = record();

    await service.ingest([r], DEVICE);
    await service.ingest([r], DEVICE); // ack lost on the way back
    await service.ingest([r], DEVICE); // and again

    assert.equal(repo.records.size, 1);
  });

  test('a retry does not revert a PHC acknowledgement', async () => {
    // The realistic sequence: worker uploads, drives away, PHC acknowledges,
    // worker's phone retries a batch it never got an ack for.
    const r = record({ tier: 'RED' });
    await service.ingest([r], DEVICE);

    await service.setReferralState({
      recordId: r.recordId,
      referralState: 'seen_at_phc',
      referralUpdatedBy: 'phc-042',
    });

    await service.ingest([r], DEVICE);

    assert.equal(repo.records.get(r.recordId).referralState, 'seen_at_phc');
  });

  test('a stale referralState in the payload is ignored, not written', async () => {
    const r = record({ tier: 'RED' });
    await service.ingest([r], DEVICE);
    await service.setReferralState({
      recordId: r.recordId,
      referralState: 'closed',
      referralUpdatedBy: 'phc-042',
    });

    // A phone offline for six hours still holds referralState: null.
    await service.ingest([{ ...r, referralState: null }], DEVICE);

    assert.equal(repo.records.get(r.recordId).referralState, 'closed');
  });

  test('an interrupted flush resumes without re-creating records', async () => {
    const rs = [record(), record(), record(), record(), record()];

    // Coverage for two records, then it drops.
    await service.ingest(rs.slice(0, 2), DEVICE);
    assert.equal(repo.records.size, 2);

    // Back in coverage. The device retries the whole tail; the first two were
    // acked so it does not resend them, but even if it did:
    await service.ingest(rs, DEVICE);

    assert.equal(repo.records.size, 5, 'exactly five referrals, no duplicates');
  });
});

describe('acknowledgements', () => {
  test('a state change is readable by the owning worker', async () => {
    const r = record({ tier: 'RED' });
    await service.ingest([r], DEVICE);
    await service.setReferralState({
      recordId: r.recordId,
      referralState: 'patient_contacted',
      referralUpdatedBy: 'phc-042',
    });

    const { acks, cursor } = await service.acks({ whvId: 'whv-021' });
    assert.equal(acks.length, 1);
    assert.equal(acks[0].referralState, 'patient_contacted');
    assert.ok(cursor, 'a cursor is returned so the next poll can advance');
  });

  test('a worker cannot see another worker’s referrals', async () => {
    const r = record({ tier: 'RED' });
    await service.ingest([r], DEVICE);
    await service.setReferralState({
      recordId: r.recordId,
      referralState: 'acknowledged',
    });

    const { acks } = await service.acks({ whvId: 'whv-777' });
    assert.equal(acks.length, 0);
  });

  test('the cursor excludes acks already seen', async () => {
    const a = record({ tier: 'RED' });
    const b = record({ tier: 'AMBER' });
    await service.ingest([a, b], DEVICE);

    await service.setReferralState({ recordId: a.recordId, referralState: 'acknowledged' });
    const first = await service.acks({ whvId: 'whv-021' });

    await new Promise((r) => setTimeout(r, 2));
    await service.setReferralState({ recordId: b.recordId, referralState: 'closed' });

    const second = await service.acks({ whvId: 'whv-021', since: first.cursor });
    assert.equal(second.acks.length, 1);
    assert.equal(second.acks[0].recordId, b.recordId);
  });

  test('an unknown referral state is refused', async () => {
    const r = record();
    await service.ingest([r], DEVICE);

    const res = await service.setReferralState({
      recordId: r.recordId,
      referralState: 'diagnosed_with_af',
    });
    assert.equal(res.ok, false);
    assert.equal(res.code, 'invalid_referral_state');
  });

  test('acking an unknown record fails rather than creating one', async () => {
    const res = await service.setReferralState({
      recordId: uuid(),
      referralState: 'acknowledged',
    });
    assert.equal(res.ok, false);
    assert.equal(res.code, 'unknown_record');
    assert.equal(repo.records.size, 0);
  });

  test('no referral state names a condition', async () => {
    // Non-negotiable 1. The ack vocabulary is the one channel through which
    // outside text could reach a worker's screen, so it is checked here too.
    const banned = ['fibrillation', 'arrhythmia', 'diagnos'];
    for (const state of REFERRAL_STATES) {
      for (const word of banned) {
        assert.ok(
          !state.toLowerCase().includes(word),
          `referral state "${state}" contains "${word}"`,
        );
      }
    }
  });
});

describe('queue', () => {
  test('RED leads, then AMBER — clinical order, not alphabetical', async () => {
    await service.ingest(
      [
        record({ tier: 'GREEN' }),
        record({ tier: 'RED', decidedBy: 'cnn', modelVersion: 'af-cnn-int8-1.0+cal1' }),
        record({ tier: 'AMBER' }),
      ],
      DEVICE,
    );

    const q = await service.queue({ phcId: 'phc-042' });
    assert.deepEqual(q.map((r) => r.tier), ['RED', 'AMBER', 'GREEN']);
  });

  test('filters to one PHC', async () => {
    await service.ingest([record()], DEVICE);
    const q = await service.queue({ phcId: 'phc-999' });
    assert.equal(q.length, 0);
  });

  test('no queue field carries a diagnosis string', async () => {
    await service.ingest([record({ tier: 'RED' })], DEVICE);
    const [row] = await service.queue({ phcId: 'phc-042' });

    const blob = JSON.stringify(row).toLowerCase();
    for (const word of ['fibrillation', 'arrhythmia']) {
      assert.ok(!blob.includes(word), `queue row contains "${word}"`);
    }
  });
});

describe('the v2 amendment', () => {
  test('a PPG-escalated RED can explain itself', async () => {
    // The reason schemaVersion went to 2. Under v1 these keys had nowhere to
    // live and additionalProperties:false rejected them, so a PHC saw a RED
    // with no evidence for why it was not an AMBER.
    const r = record({
      tier: 'RED',
      decidedBy: 'rules+cnn',
      modelVersion: 'af-cnn-int8-1.0+cal1',
      rrIrregularityScore: 0.61,
      cnnScore: 0.88,
      ppgResult: 'irregular',
      ppgMeanHr: 88,
      ppgIrregularityScore: 0.58,
      ppgPerfusionIndex: 1.4,
      pulseDeficitBpm: 11.2,
      perfusedBeatFraction: 0.72,
      nonPerfusingBeats: 9,
      medianPttMs: 268,
      fusionValid: true,
      ppgCorroboration: 'pulseDeficit',
    });

    const [res] = await service.ingest([r], DEVICE);
    assert.equal(res.status, 'accepted');

    const stored = repo.records.get(r.recordId);
    assert.equal(stored.ppgCorroboration, 'pulseDeficit');
    assert.equal(stored.pulseDeficitBpm, 11.2);
  });

  test('ppgCorroboration mirrors the Dart PpgCorroboration enum', async () => {
    assert.deepEqual(
      RECORD_SCHEMA.properties.ppgCorroboration.enum.filter((v) => v !== null),
      ['none', 'unusable', 'agreed', 'pulseDeficit', 'nonPerfusingBeats'],
    );
  });
});
