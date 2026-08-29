import snowflake from 'snowflake-sdk';

/**
 * Snowflake side of the analytics export. See contracts/analytics.md.
 *
 * Additive only — nothing in routes.js or service.js calls into this. A separate
 * script (scripts/export_to_snowflake.js) reads from MongoRepo and writes here.
 *
 * [camelCase Mongo field, snake_case Snowflake column] — contracts/analytics.md §2's
 * field list, in the order every row and bind array below follows.
 */
export const COLUMNS = [
  ['recordId', 'record_id'],
  ['patientPseudoId', 'patient_pseudo_id'],
  ['phcId', 'phc_id'],
  ['capturedAt', 'captured_at'],
  ['ageBand', 'age_band'],
  ['villageCode', 'village_code'],
  ['sex', 'sex'],
  ['tier', 'tier'],
  ['referralState', 'referral_state'],
  ['clinicianOutcome', 'clinician_outcome'],
  ['clinicianOutcomeAt', 'clinician_outcome_at'],
  ['modelVersion', 'model_version'],
];

export const EXPORT_FIELDS = COLUMNS.map(([camel]) => camel);
const COLUMN_NAMES = COLUMNS.map(([, snake]) => snake);
const NON_KEY_COLUMNS = COLUMN_NAMES.filter((c) => c !== 'record_id');

/** A Mongo screening doc -> one row, in COLUMN_NAMES order. Missing field -> null. */
export function toRow(doc) {
  return COLUMNS.map(([camel]) => doc[camel] ?? null);
}

const MERGE_SQL = `
  MERGE INTO screenings AS t
  USING screenings_staging AS s
  ON t.record_id = s.record_id
  WHEN MATCHED THEN UPDATE SET ${NON_KEY_COLUMNS.map((c) => `t.${c} = s.${c}`).join(', ')}
  WHEN NOT MATCHED THEN INSERT (${COLUMN_NAMES.join(', ')})
    VALUES (${COLUMN_NAMES.map((c) => `s.${c}`).join(', ')})
`;

export class SnowflakeRepo {
  constructor(connection) {
    this.connection = connection;
  }

  static connect(config = {}) {
    const connection = snowflake.createConnection({
      account: config.account ?? process.env.SNOWFLAKE_ACCOUNT,
      username: config.username ?? process.env.SNOWFLAKE_USER,
      password: config.password ?? process.env.SNOWFLAKE_PASSWORD,
      warehouse: config.warehouse ?? process.env.SNOWFLAKE_WAREHOUSE,
      database: config.database ?? process.env.SNOWFLAKE_DATABASE,
      schema: config.schema ?? process.env.SNOWFLAKE_SCHEMA ?? 'PUBLIC',
    });

    return new Promise((resolve, reject) => {
      connection.connect((err, conn) => (err ? reject(err) : resolve(new SnowflakeRepo(conn))));
    });
  }

  exec(sqlText, binds) {
    return new Promise((resolve, reject) => {
      this.connection.execute({
        sqlText,
        ...(binds ? { binds } : {}),
        complete: (err, _stmt, rows) => (err ? reject(err) : resolve(rows)),
      });
    });
  }

  /** contracts/analytics.md §4-5: the fact table plus its one rollup view. */
  async ensureSchema() {
    await this.exec(`
      CREATE TABLE IF NOT EXISTS screenings (
        record_id            STRING PRIMARY KEY,
        patient_pseudo_id     STRING,
        phc_id                STRING,
        captured_at           TIMESTAMP_TZ,
        age_band              STRING,
        village_code          STRING,
        sex                   STRING,
        tier                  STRING,
        referral_state        STRING,
        clinician_outcome     STRING,
        clinician_outcome_at  TIMESTAMP_TZ,
        model_version         STRING
      )
    `);
    await this.exec(`
      CREATE OR REPLACE VIEW district_tier_trends AS
      SELECT village_code, tier, DATE_TRUNC('day', captured_at) AS day, COUNT(*) AS screenings
      FROM screenings
      GROUP BY village_code, tier, day
    `);
  }

  /**
   * Stage-then-merge, contracts/analytics.md §3: one bulk array-bind INSERT (a single
   * round trip regardless of row count) into a staging table, then one MERGE keyed on
   * record_id — the same idempotent-upsert discipline contracts/sync.md §5 uses for
   * Mongo, so re-running an export can never double-count a village's screenings.
   */
  async mergeScreenings(docs) {
    if (docs.length === 0) return { merged: 0 };

    await this.exec('CREATE TEMPORARY TABLE IF NOT EXISTS screenings_staging LIKE screenings');
    await this.exec('TRUNCATE TABLE screenings_staging');
    await this.exec(
      `INSERT INTO screenings_staging (${COLUMN_NAMES.join(', ')})
       VALUES (${COLUMN_NAMES.map(() => '?').join(', ')})`,
      docs.map(toRow),
    );
    await this.exec(MERGE_SQL);

    return { merged: docs.length };
  }

  close() {
    return new Promise((resolve, reject) => {
      this.connection.destroy((err) => (err ? reject(err) : resolve()));
    });
  }
}
