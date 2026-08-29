import assert from 'node:assert/strict';
import { describe, test } from 'node:test';

import { EXPORT_FIELDS, SnowflakeRepo, toRow } from '../src/snowflake_repo.js';

/**
 * No live Snowflake account in this environment (contracts/analytics.md §6 — the
 * account is HITL-provisioned, same class of blocker ticket 017 had for Atlas), so
 * this verifies the SQL/row-shaping logic against a fake connection instead of a
 * real one, same spirit as MemoryRepo standing in for mongo_repo.js.
 */
function fakeConnection() {
  const calls = [];
  return {
    calls,
    execute({ sqlText, binds, complete }) {
      calls.push({ sqlText, binds });
      complete(null, {}, []);
    },
    destroy(cb) {
      calls.push({ sqlText: '__destroy__' });
      cb(null);
    },
  };
}

function record(overrides = {}) {
  return {
    recordId: '3f2504e0-4f89-41d3-9a0c-a1a1a1a1a1a1',
    patientPseudoId: 'a1b2c3d4e5f60718',
    phcId: 'phc-042',
    capturedAt: '2026-08-29T09:14:00+05:30',
    ageBand: '55-64',
    villageCode: 'village-042',
    sex: null,
    tier: 'GREEN',
    referralState: null,
    clinicianOutcome: null,
    clinicianOutcomeAt: null,
    modelVersion: 'rules-1.0',
    ...overrides,
  };
}

describe('toRow', () => {
  test('follows EXPORT_FIELDS order and nulls out missing fields', () => {
    const row = toRow(record({ sex: 'female' }));
    assert.equal(row.length, EXPORT_FIELDS.length);
    assert.equal(row[EXPORT_FIELDS.indexOf('recordId')], '3f2504e0-4f89-41d3-9a0c-a1a1a1a1a1a1');
    assert.equal(row[EXPORT_FIELDS.indexOf('sex')], 'female');
  });

  test('a field absent from the doc becomes null, not undefined', () => {
    const row = toRow({ recordId: 'x' });
    assert.equal(row[EXPORT_FIELDS.indexOf('villageCode')], null);
  });

  test('never carries whvId or a raw signal field — contracts/analytics.md §2', () => {
    // Not a column at all, so smuggling it into the source doc has no effect.
    assert.ok(!EXPORT_FIELDS.includes('whvId'));
    assert.ok(!EXPORT_FIELDS.includes('sqiScore'));
    assert.ok(!EXPORT_FIELDS.includes('lat'));
  });
});

describe('SnowflakeRepo.ensureSchema', () => {
  test('creates the fact table and the rollup view', async () => {
    const conn = fakeConnection();
    await new SnowflakeRepo(conn).ensureSchema();

    const sql = conn.calls.map((c) => c.sqlText).join('\n');
    assert.match(sql, /CREATE TABLE IF NOT EXISTS screenings/);
    assert.match(sql, /CREATE OR REPLACE VIEW district_tier_trends/);
    assert.match(sql, /GROUP BY village_code, tier, day/);
  });
});

describe('SnowflakeRepo.mergeScreenings', () => {
  test('does nothing for an empty batch', async () => {
    const conn = fakeConnection();
    const result = await new SnowflakeRepo(conn).mergeScreenings([]);

    assert.deepEqual(result, { merged: 0 });
    assert.equal(conn.calls.length, 0);
  });

  test('stages with one bulk array-bind insert, then one merge — not one round trip per row', async () => {
    const conn = fakeConnection();
    const docs = [record(), record({ recordId: 'r2', tier: 'RED' })];

    const result = await new SnowflakeRepo(conn).mergeScreenings(docs);
    assert.deepEqual(result, { merged: 2 });

    const [createStaging, truncate, insert, merge] = conn.calls;
    assert.match(createStaging.sqlText, /CREATE TEMPORARY TABLE IF NOT EXISTS screenings_staging/);
    assert.match(truncate.sqlText, /TRUNCATE TABLE screenings_staging/);

    assert.match(insert.sqlText, /INSERT INTO screenings_staging/);
    assert.equal(insert.binds.length, 2, 'one bind array per row, one execute call total');
    assert.deepEqual(insert.binds[0], toRow(docs[0]));
    assert.deepEqual(insert.binds[1], toRow(docs[1]));

    assert.match(merge.sqlText, /MERGE INTO screenings AS t/);
    assert.match(merge.sqlText, /ON t\.record_id = s\.record_id/);
    assert.match(merge.sqlText, /WHEN MATCHED THEN UPDATE SET/);
    assert.match(merge.sqlText, /WHEN NOT MATCHED THEN INSERT/);

    assert.equal(conn.calls.length, 4, 'exactly 4 statements regardless of batch size');
  });
});

describe('SnowflakeRepo.close', () => {
  test('destroys the underlying connection', async () => {
    const conn = fakeConnection();
    await new SnowflakeRepo(conn).close();
    assert.ok(conn.calls.some((c) => c.sqlText === '__destroy__'));
  });
});
