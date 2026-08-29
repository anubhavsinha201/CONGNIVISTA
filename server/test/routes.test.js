import assert from 'node:assert/strict';
import { after, before, describe, test } from 'node:test';

import { MemoryRepo } from '../src/memory_repo.js';
import { buildApp, MAX_BATCH } from '../src/routes.js';
import { SyncService } from '../src/service.js';

let server;
let base;
let repo;

function record(overrides = {}) {
  return {
    recordId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
    schemaVersion: 4,
    ageBand: '55-64',
    villageCode: 'village-042',
    patientPseudoId: 'a1b2c3d4e5f60718',
    whvId: 'whv-021',
    phcId: 'phc-042',
    capturedAt: '2026-08-29T09:14:00+05:30',
    ecgDurationSec: 30,
    sqiScore: 0.82,
    motionRejected: false,
    leadOffDetected: false,
    decidedBy: 'rules',
    tier: 'GREEN',
    modelVersion: 'rules-1.0',
    syncState: 'pending',
    ...overrides,
  };
}

const post = (path, body, token) =>
  fetch(`${base}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });

before(async () => {
  repo = new MemoryRepo();
  repo.addDevice({ token: 'tok-021', whvId: 'whv-021', phcId: 'phc-042' });
  repo.addDevice({ token: 'tok-777', whvId: 'whv-777', phcId: 'phc-042' });

  const app = buildApp({ repo, service: new SyncService(repo) });
  server = app.listen(0);
  await new Promise((r) => server.once('listening', r));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server?.close());

describe('auth', () => {
  test('a request with no token is refused', async () => {
    const res = await post('/v1/records:batch', { records: [record()] });
    assert.equal(res.status, 401);
    assert.equal(repo.records.size, 0);
  });

  test('an unknown token is refused', async () => {
    const res = await post('/v1/records:batch', { records: [record()] }, 'nope');
    assert.equal(res.status, 401);
  });

  test('a device cannot upload records attributed to another worker', async () => {
    // One compromised handset must not be able to forge referrals for the
    // whole district.
    const res = await post(
      '/v1/records:batch',
      { records: [record({ whvId: 'whv-021' })] },
      'tok-777',
    );
    assert.equal(res.status, 403);
    assert.equal(repo.records.size, 0);
  });

  test('acks are scoped by token, not by a query parameter', async () => {
    const res = await fetch(`${base}/v1/acks?whvId=whv-021`, {
      headers: { Authorization: 'Bearer tok-777' },
    });
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.deepEqual(body.acks, [], 'whv-777 saw whv-021 data via the query string');
  });
});

describe('batch limits', () => {
  test('a batch larger than the contract allows is refused', async () => {
    const records = Array.from({ length: MAX_BATCH + 1 }, (_, i) =>
      record({ recordId: `3f2504e0-4f89-41d3-9a0c-${String(i).padStart(12, '0')}` }),
    );
    const res = await post('/v1/records:batch', { records }, 'tok-021');
    assert.equal(res.status, 413);
  });

  test('a non-array body is a 400, not a crash', async () => {
    const res = await post('/v1/records:batch', { records: 'nope' }, 'tok-021');
    assert.equal(res.status, 400);
  });
});

describe('round trip', () => {
  test('upload, ack from the dashboard, pull the ack back', async () => {
    const r = record({
      recordId: '3f2504e0-4f89-41d3-9a0c-0305e82c3999',
      tier: 'RED',
      decidedBy: 'rules+cnn',
      modelVersion: 'af-cnn-int8-1.0+cal1',
    });

    const up = await post('/v1/records:batch', { records: [r] }, 'tok-021');
    assert.equal(up.status, 200);
    assert.deepEqual((await up.json()).results, [
      { recordId: r.recordId, status: 'accepted' },
    ]);

    // The dashboard sees it, RED first.
    const q = await (await fetch(`${base}/v1/queue?phcId=phc-042`)).json();
    assert.equal(q.records[0].recordId, r.recordId);

    // A nurse acknowledges it.
    const ack = await post('/v1/acks', {
      recordId: r.recordId,
      referralState: 'seen_at_phc',
      referralUpdatedBy: 'phc-042',
    });
    assert.equal(ack.status, 200);

    // It reaches the worker's phone on the next pull.
    const pulled = await (
      await fetch(`${base}/v1/acks`, {
        headers: { Authorization: 'Bearer tok-021' },
      })
    ).json();

    assert.equal(pulled.acks.length, 1);
    assert.equal(pulled.acks[0].referralState, 'seen_at_phc');
    assert.ok(pulled.cursor);
  });

  test('a dashboard ack with a state outside the enum is a 400', async () => {
    const res = await post('/v1/acks', {
      recordId: '3f2504e0-4f89-41d3-9a0c-0305e82c3999',
      referralState: 'has_atrial_fibrillation',
    });
    assert.equal(res.status, 400);
  });

  test('healthz answers without a token', async () => {
    const res = await fetch(`${base}/healthz`);
    assert.equal(res.status, 200);
  });
});
