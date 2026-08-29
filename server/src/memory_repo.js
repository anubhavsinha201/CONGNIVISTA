import { TIER_RANK } from './service.js';

/**
 * In-memory repo, behaviourally identical to MongoRepo for everything
 * SyncService relies on — including the unique-on-recordId upsert.
 *
 * Used by `npm test`, so the service's logic is verified without a database
 * running, and by `DEMO=1 npm start` so the stage demo cannot be broken by a
 * container that did not come up.
 */
export class MemoryRepo {
  constructor() {
    /** @type {Map<string, object>} keyed by recordId — this map IS the unique index */
    this.records = new Map();
    this.devices = new Map();
    /** Set true to make the next upsertRecords throw, for the db-down test. */
    this.failNextWrite = false;
  }

  async ensureIndexes() {}

  async upsertRecords(docs) {
    if (this.failNextWrite) {
      this.failNextWrite = false;
      throw new Error('simulated database outage');
    }

    let upserted = 0;
    let modified = 0;
    for (const doc of docs) {
      const existing = this.records.get(doc.recordId);
      if (existing) {
        // Merge, so server-owned referral fields already on the document
        // survive a device re-upload — same as $set over an absent key.
        this.records.set(doc.recordId, { ...existing, ...doc });
        modified++;
      } else {
        this.records.set(doc.recordId, { ...doc });
        upserted++;
      }
    }
    return { upserted, modified };
  }

  async findAcks({ whvId, since }) {
    return [...this.records.values()]
      .filter(
        (r) =>
          r.whvId === whvId &&
          r.referralState != null &&
          (!since || (r.referralUpdatedAt ?? '') > since),
      )
      .sort((a, b) => (a.referralUpdatedAt ?? '').localeCompare(b.referralUpdatedAt ?? ''))
      .map(({ recordId, referralState, referralUpdatedAt, referralUpdatedBy }) => ({
        recordId,
        referralState,
        referralUpdatedAt,
        referralUpdatedBy,
      }));
  }

  async setReferralState({ recordId, referralState, referralUpdatedBy, referralUpdatedAt }) {
    const r = this.records.get(recordId);
    if (!r) return false;
    Object.assign(r, { referralState, referralUpdatedBy, referralUpdatedAt });
    return true;
  }

  async queue({ phcId, limit }) {
    return [...this.records.values()]
      .filter((r) => !phcId || r.phcId === phcId)
      .sort(
        (a, b) =>
          (TIER_RANK[a.tier] ?? 9) - (TIER_RANK[b.tier] ?? 9) ||
          (b.capturedAt ?? '').localeCompare(a.capturedAt ?? ''),
      )
      .slice(0, limit);
  }

  async deviceForToken(token) {
    return this.devices.get(token) ?? null;
  }

  addDevice({ token, whvId, phcId }) {
    this.devices.set(token, { token, whvId, phcId });
  }

  async close() {}
}
