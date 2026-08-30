/**
 * Reads the same dataset back out of BOTH MongoDB and Snowflake and prints them
 * side by side.
 *
 *   npm run verify:tracks
 *
 * Point of this script: "we used MongoDB and Snowflake" is a claim, and a
 * screenshot of a dashboard does not distinguish a live warehouse from a
 * hard-coded array. This connects to both systems, counts the same rows in each,
 * and shows the row counts agreeing — which is checkable while someone watches.
 *
 * It also prints the Snowflake column list, because the interesting property of
 * this export is not that data arrived but that `whvId`, `lat`, `lon` and every
 * raw signal field did NOT (contracts/analytics.md §2, non-negotiable 5).
 *
 * Read-only. Nothing here writes to either system.
 */

import { MongoRepo } from '../src/mongo_repo.js';
import { SnowflakeRepo } from '../src/snowflake_repo.js';

const MONGO_URI = process.env.MONGO_URI ?? 'mongodb://127.0.0.1:27017';
const DB_NAME = process.env.MONGO_DB ?? 'arogyax';

const pad = (s, n) => String(s).padEnd(n);
const rule = (c = '-') => console.log(c.repeat(64));

async function main() {
  const mongo = await MongoRepo.connect(MONGO_URI, DB_NAME);
  const sf = await SnowflakeRepo.connect();

  try {
    rule('=');
    console.log('  ArogyaX — MongoDB ⇄ Snowflake round trip');
    rule('=');

    // ---- Counts, from each system independently -------------------------
    const mTotal = await mongo.records.countDocuments({});
    const sTotal = (await sf.exec('SELECT COUNT(*) AS N FROM screenings'))[0].N;

    console.log(`\n  MongoDB  screenings collection : ${mTotal}`);
    console.log(`  Snowflake SCREENINGS table     : ${sTotal}`);
    console.log(
      mTotal === Number(sTotal)
        ? '  ✓ row counts agree — the export is current'
        : `  ! counts differ by ${Math.abs(mTotal - Number(sTotal))} — run: npm run export:snowflake`,
    );

    // ---- Tier mix, computed on each side --------------------------------
    const mTiers = Object.fromEntries(
      (await mongo.records
        .aggregate([{ $group: { _id: '$tier', n: { $sum: 1 } } }])
        .toArray()).map((r) => [r._id, r.n]),
    );
    const sTiers = Object.fromEntries(
      (await sf.exec('SELECT tier, COUNT(*) AS N FROM screenings GROUP BY tier'))
        .map((r) => [r.TIER, Number(r.N)]),
    );

    console.log('\n  Referral tier          MongoDB   Snowflake');
    rule();
    for (const tier of ['RED', 'ORANGE', 'YELLOW', 'GREEN', 'RETAKE']) {
      const m = mTiers[tier] ?? 0;
      const s = sTiers[tier] ?? 0;
      console.log(`  ${pad(tier, 22)} ${pad(m, 9)} ${pad(s, 9)} ${m === s ? '✓' : '✗'}`);
    }

    // ---- One record, proven present in both ------------------------------
    const sample = await mongo.records.findOne(
      { tier: 'RED' },
      { projection: { _id: 0, recordId: 1, patientPseudoId: 1, villageCode: 1, tier: 1 } },
    );
    if (sample) {
      const back = await sf.exec(
        `SELECT record_id, tier, village_code FROM screenings WHERE record_id = '${sample.recordId}'`,
      );
      console.log('\n  Same record, fetched from each system by id');
      rule();
      console.log(`  id          ${sample.recordId}`);
      console.log(`  MongoDB     tier=${sample.tier} village=${sample.villageCode}`);
      console.log(
        back.length
          ? `  Snowflake   tier=${back[0].TIER} village=${back[0].VILLAGE_CODE}  ✓ present`
          : '  Snowflake   ✗ NOT FOUND — export is stale',
      );
    }

    // ---- The rollup view -------------------------------------------------
    const trend = await sf.exec(
      `SELECT village_code, tier, COUNT(*) AS N
         FROM screenings WHERE tier IN ('RED','ORANGE')
         GROUP BY village_code, tier ORDER BY N DESC LIMIT 6`,
    );
    console.log('\n  Snowflake aggregate — referral load by locality');
    rule();
    for (const r of trend) {
      console.log(`  ${pad(r.VILLAGE_CODE, 16)} ${pad(r.TIER, 8)} ${r.N}`);
    }

    // ---- What was deliberately NOT exported ------------------------------
    const cols = (await sf.exec(
      "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SCREENINGS'",
    )).map((c) => c.COLUMN_NAME.toLowerCase());

    const mustBeAbsent = ['whv_id', 'lat', 'lon', 'ecg_waveform_ref', 'sqi_score', 'cnn_score'];
    const leaked = mustBeAbsent.filter((c) => cols.includes(c));

    console.log('\n  Privacy — what reached the warehouse');
    rule();
    console.log(`  columns present : ${cols.length}`);
    console.log(`  ${cols.join(', ')}`);
    console.log(
      leaked.length === 0
        ? '\n  ✓ no health-worker id, no location, no raw signal — by design, not omission'
        : `\n  ✗ LEAKED: ${leaked.join(', ')}`,
    );

    const synthetic = (await sf.exec(
      "SELECT COUNT(*) AS N FROM screenings WHERE phc_id = 'phc-demo'",
    ))[0].N;
    console.log(`\n  Of ${sTotal} rows, ${synthetic} are SYNTHETIC (phc_id='phc-demo').`);
    if (Number(synthetic) === Number(sTotal)) {
      console.log('  Every row in this warehouse is fabricated demo data, not a measurement.');
    }
    rule('=');
  } finally {
    await sf.close();
    await mongo.close();
  }
}

main().catch((err) => {
  console.error('[verify_tracks] failed:', err);
  process.exit(1);
});
