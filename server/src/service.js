import { checkRecord, forStorage, REFERRAL_STATES } from './validate.js';

/**
 * The sync service's behaviour, independent of MongoDB and of Express.
 *
 * Takes a `repo` with a small, boring interface (below), so the interesting
 * logic — idempotency, per-record results, ownership — is testable with an
 * in-memory repo and no database running. `mongo_repo.js` is the real one.
 *
 *   repo.upsertRecords(docs)            -> { upserted, modified }
 *   repo.findAcks({ whvId, since })     -> [ { recordId, referralState, ... } ]
 *   repo.setReferralState(args)         -> boolean (false if no such record)
 *   repo.queue({ phcId, limit })        -> [ record ]
 */
export class SyncService {
  constructor(repo) {
    this.repo = repo;
  }

  /**
   * `POST /v1/records:batch`.
   *
   * Returns one result per submitted record, in order. contracts/sync.md
   * section 5: the device marks state from these results and from nothing else,
   * so a record missing from this array is a bug on this side.
   */
  async ingest(records, { whvId, phcId }) {
    const results = [];
    const toStore = [];

    for (const record of records) {
      const id = record?.recordId;

      if (typeof id !== 'string') {
        // No id means nothing to key an idempotent write on, and nothing for
        // the device to reconcile a result against.
        results.push({ recordId: null, status: 'rejected', code: 'missing_record_id' });
        continue;
      }

      const check = checkRecord(record);
      if (!check.ok) {
        // Rejected, not retryable: the bytes will be identical next time.
        results.push({
          recordId: id,
          status: 'rejected',
          code: 'schema_invalid',
          detail: check.detail,
        });
        continue;
      }

      toStore.push(forStorage(record, { whvId, phcId }));
      results.push({ recordId: id, status: 'accepted' });
    }

    if (toStore.length > 0) {
      try {
        await this.repo.upsertRecords(toStore);
      } catch (err) {
        // The database is the transient thing here, not the records. Downgrade
        // every would-be acceptance to retryable so the device keeps them
        // pending — marking them synced on a failed write would drop referrals
        // silently, which is the worst outcome this service can produce.
        const failed = new Set(toStore.map((d) => d.recordId));
        for (const r of results) {
          if (failed.has(r.recordId)) {
            r.status = 'retryable';
            r.code = 'db_unavailable';
          }
        }
      }
    }

    return results;
  }

  /** `GET /v1/acks`. whvId comes from the token, never the query string. */
  async acks({ whvId, since }) {
    const acks = await this.repo.findAcks({ whvId, since });

    // The cursor is the newest referralUpdatedAt actually returned, so a
    // subsequent poll cannot skip an ack written while this one was in flight.
    let cursor = null;
    for (const a of acks) {
      if (a.referralUpdatedAt && (!cursor || a.referralUpdatedAt > cursor)) {
        cursor = a.referralUpdatedAt;
      }
    }
    return { acks, cursor };
  }

  /** `POST /v1/acks`, written by the PHC dashboard. */
  async setReferralState({ recordId, referralState, referralUpdatedBy }) {
    if (!REFERRAL_STATES.includes(referralState)) {
      return { ok: false, code: 'invalid_referral_state' };
    }
    if (typeof recordId !== 'string' || !recordId) {
      return { ok: false, code: 'missing_record_id' };
    }

    const updated = await this.repo.setReferralState({
      recordId,
      referralState,
      referralUpdatedBy: referralUpdatedBy ?? null,
      referralUpdatedAt: new Date().toISOString(),
    });

    return updated ? { ok: true } : { ok: false, code: 'unknown_record' };
  }

  /** `GET /v1/queue`, the dashboard's referral list. */
  async queue({ phcId, limit = 200 }) {
    return this.repo.queue({ phcId, limit });
  }
}

/** Sort order for the referral queue: worst tier first, then newest. */
export const TIER_RANK = { RED: 0, AMBER: 1, GREEN: 2, RETAKE: 3 };
