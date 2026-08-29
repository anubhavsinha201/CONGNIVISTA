import { MongoRepo } from '../src/mongo_repo.js';
import { EXPORT_FIELDS, SnowflakeRepo } from '../src/snowflake_repo.js';

/**
 * Ticket 018 / contracts/analytics.md. Run manually or from an external scheduler
 * (cron, a Snowflake Task) — not triggered by the sync service, not a live stream.
 * A screening is already sync-when-convenient (CLAUDE.md non-negotiable 4); there is
 * no case here for a real-time pipe. Additive only: this reads MongoDB, it does not
 * sit in front of anything the sync service or dashboard already does.
 *
 *   node scripts/export_to_snowflake.js               # every screening
 *   node scripts/export_to_snowflake.js --since 2026-08-29T00:00:00Z   # incremental
 */

const MONGO_URI = process.env.MONGO_URI ?? 'mongodb://127.0.0.1:27017';
const DB_NAME = process.env.MONGO_DB ?? 'arogyax';

const sinceFlag = process.argv.indexOf('--since');
const since = sinceFlag !== -1 ? process.argv[sinceFlag + 1] : undefined;

// _id: 0 keeps Mongo's ObjectId out of the export; the field list itself is
// contracts/analytics.md §2 — everything not in EXPORT_FIELDS is left behind on
// purpose (whvId, raw ECG/PPG signal fields, lat/lon), not by omission.
const projection = { _id: 0 };
for (const field of EXPORT_FIELDS) projection[field] = 1;

async function main() {
  const mongo = await MongoRepo.connect(MONGO_URI, DB_NAME);
  const sf = await SnowflakeRepo.connect();

  try {
    await sf.ensureSchema();

    const query = since ? { capturedAt: { $gt: since } } : {};
    const docs = await mongo.records.find(query, { projection }).toArray();

    const { merged } = await sf.mergeScreenings(docs);
    console.log(
      `[export_to_snowflake] merged ${merged} screening${merged === 1 ? '' : 's'}` +
        (since ? ` captured after ${since}` : ' (full export)'),
    );
  } finally {
    await sf.close();
    await mongo.close();
  }
}

main().catch((err) => {
  console.error('[export_to_snowflake] failed:', err);
  process.exit(1);
});
