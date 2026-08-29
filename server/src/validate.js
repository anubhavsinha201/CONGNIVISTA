import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const here = dirname(fileURLToPath(import.meta.url));

/**
 * The schema is read from contracts/ at startup, not vendored into the server.
 *
 * One copy, three consumers: the Dart record class, this validator, and
 * ml/reference/validate_record.py. A vendored duplicate is a copy that drifts
 * on the day someone edits the contract and forgets the server exists.
 */
export const RECORD_SCHEMA = JSON.parse(
  readFileSync(join(here, '..', '..', 'contracts', 'record.schema.json'), 'utf8'),
);

/** Server-owned. See contracts/sync.md section 3. */
export const SERVER_OWNED = [
  'referralState',
  'referralUpdatedAt',
  'referralUpdatedBy',
  'clinicianOutcome',
  'clinicianOutcomeAt',
];

/** Device bookkeeping. Meaningless server-side, so it is not stored. */
export const DEVICE_ONLY = ['syncState', 'syncedAt'];

export const REFERRAL_STATES = RECORD_SCHEMA.properties.referralState.enum.filter(
  (v) => v !== null,
);

/**
 * What the clinician found. Distinct from REFERRAL_STATES, which tracks process.
 *
 * referralState answers "did anything happen"; this answers "what was it". Only
 * the second can be used as a training label, which is why both exist.
 */
export const CLINICIAN_OUTCOMES =
  RECORD_SCHEMA.properties.clinicianOutcome.enum.filter((v) => v !== null);

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

const validateRecord = ajv.compile(RECORD_SCHEMA);

/**
 * Validates one uploaded record.
 *
 * The device omits the server-owned fields (ScreeningRecord.toSyncJson), and
 * they are optional in the schema, so a payload validates as-is.
 *
 * @returns {{ok: true} | {ok: false, detail: string}}
 */
export function checkRecord(record) {
  if (validateRecord(record)) return { ok: true };
  const detail = (validateRecord.errors ?? [])
    .slice(0, 3)
    .map((e) => `${e.instancePath || '/'} ${e.message}`)
    .join('; ');
  return { ok: false, detail };
}

/**
 * Strips what the device may not write.
 *
 * A phone that has been offline for six hours holds a stale referralState.
 * Letting its retry write that back would silently revert an acknowledgement a
 * PHC nurse made an hour ago. The device already omits these fields; this is
 * the half of the guarantee that does not depend on the client behaving.
 */
export function forStorage(record, { whvId, phcId }) {
  const doc = { ...record };
  for (const k of [...SERVER_OWNED, ...DEVICE_ONLY]) delete doc[k];

  // Identity comes from the token, never the payload. Checked in auth.js; this
  // makes it true in the stored document regardless.
  doc.whvId = whvId;
  if (phcId) doc.phcId = phcId;

  doc.receivedAt = new Date().toISOString();
  return doc;
}
