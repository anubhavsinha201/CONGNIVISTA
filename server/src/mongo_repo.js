import { MongoClient } from 'mongodb';

import { TIER_RANK } from './service.js';

/**
 * MongoDB implementation of the repo interface consumed by SyncService.
 *
 * Why this file exists at all: MongoDB has no supported path from a Flutter
 * client to a cluster. Atlas Device Sync, the Realm/Atlas Device SDKs, and the
 * Atlas Data API with its HTTPS Endpoints all reached end-of-life on
 * 2025-09-30, and MongoDB's own guidance is a self-managed API over the native
 * driver. The alternative — mongo_dart dialling Atlas straight from the
 * handset — needs cluster credentials inside the APK and an IP allowlist of
 * 0.0.0.0/0. See contracts/sync.md section 1.
 */
export class MongoRepo {
  constructor(db) {
    this.db = db;
    this.records = db.collection('screenings');
    this.devices = db.collection('devices');
  }

  static async connect(uri, dbName = 'arogyax') {
    const client = new MongoClient(uri);
    await client.connect();
    const repo = new MongoRepo(client.db(dbName));
    repo.client = client;
    await repo.ensureIndexes();
    return repo;
  }

  async ensureIndexes() {
    // The load-bearing one. Everything about retry safety rests on this being
    // unique: it is what turns a re-upload into an overwrite instead of a
    // second referral for the same patient.
    await this.records.createIndex({ recordId: 1 }, { unique: true });
    await this.records.createIndex({ phcId: 1, tier: 1, capturedAt: -1 });
    await this.records.createIndex({ whvId: 1, referralUpdatedAt: -1 });
    await this.devices.createIndex({ token: 1 }, { unique: true });
  }

  async upsertRecords(docs) {
    if (docs.length === 0) return { upserted: 0, modified: 0 };

    const res = await this.records.bulkWrite(
      docs.map((doc) => ({
        updateOne: {
          filter: { recordId: doc.recordId },
          // $set of the screening fields only. The referral* fields are absent
          // from `doc` (stripped in forStorage), so a device retry leaves a
          // PHC acknowledgement untouched rather than reverting it.
          update: { $set: doc },
          upsert: true,
        },
      })),
      { ordered: false },
    );

    return { upserted: res.upsertedCount, modified: res.modifiedCount };
  }

  async findAcks({ whvId, since }) {
    const q = {
      whvId,
      $or: [{ referralState: { $ne: null } }, { clinicianOutcome: { $ne: null } }],
    };
    if (since) q.referralUpdatedAt = { $gt: since };

    const rows = await this.records
      .find(q, {
        projection: {
          _id: 0,
          recordId: 1,
          referralState: 1,
          referralUpdatedAt: 1,
          referralUpdatedBy: 1,
          clinicianOutcome: 1,
          clinicianOutcomeAt: 1,
        },
      })
      .sort({ referralUpdatedAt: 1 })
      .limit(500)
      .toArray();

    // Fields absent from an older document come back as `undefined` from the
    // driver, not `null` - normalise so the wire shape is stable either way.
    return rows.map((r) => ({
      ...r,
      clinicianOutcome: r.clinicianOutcome ?? null,
      clinicianOutcomeAt: r.clinicianOutcomeAt ?? null,
    }));
  }

  // Takes the pre-built patch from SyncService and $sets exactly those keys.
  // Mongo's $set is inherently partial, so a referral-state-only call (no
  // clinicianOutcome key present) leaves an existing outcome untouched - see
  // the doc comment on SyncService.setReferralState.
  async setReferralState({ recordId, ...fields }) {
    const res = await this.records.updateOne({ recordId }, { $set: fields });
    return res.matchedCount > 0;
  }

  async queue({ phcId, limit }) {
    const q = phcId ? { phcId } : {};
    const rows = await this.records
      .find(q, { projection: { _id: 0 } })
      .sort({ capturedAt: -1 })
      .limit(limit)
      .toArray();

    // Tier order is clinical, not lexical: RED must lead, and 'AMBER' < 'RED'
    // alphabetically. Sorted here rather than in Mongo to keep the index on
    // capturedAt doing the work that matters.
    return rows.sort(
      (a, b) => (TIER_RANK[a.tier] ?? 9) - (TIER_RANK[b.tier] ?? 9),
    );
  }

  async deviceForToken(token) {
    return this.devices.findOne({ token }, { projection: { _id: 0 } });
  }

  async close() {
    await this.client?.close();
  }
}
