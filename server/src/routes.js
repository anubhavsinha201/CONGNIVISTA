import express from 'express';

/** contracts/sync.md section 5: at most 25 records per batch. */
export const MAX_BATCH = 25;

/**
 * Resolves the bearer token to a device.
 *
 * The identity of a record is taken from the token, never from the payload. A
 * device may only write records attributed to itself; a payload whvId that
 * disagrees is a 403 rather than a silent overwrite, because the alternative is
 * one compromised handset being able to forge referrals for every worker in the
 * district.
 */
export function authMiddleware(repo) {
  return async (req, res, next) => {
    const header = req.get('authorization') ?? '';
    const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';

    if (!token) {
      return res.status(401).json({ error: 'missing bearer token' });
    }

    let device;
    try {
      device = await repo.deviceForToken(token);
    } catch {
      return res.status(503).json({ error: 'auth backend unavailable' });
    }

    if (!device) return res.status(401).json({ error: 'unknown device token' });

    req.device = device;
    next();
  };
}

export function buildApp({ repo, service, dashboardOrigin = '*' }) {
  const app = express();
  app.use(express.json({ limit: '2mb' }));

  app.use((req, res, next) => {
    res.set('Access-Control-Allow-Origin', dashboardOrigin);
    res.set('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    if (req.method === 'OPTIONS') return res.sendStatus(204);
    next();
  });

  app.get('/healthz', (_req, res) => res.json({ ok: true }));

  const auth = authMiddleware(repo);

  // ---- Device endpoints --------------------------------------------------

  app.post('/v1/records:batch', auth, async (req, res) => {
    const records = req.body?.records;
    if (!Array.isArray(records)) {
      return res.status(400).json({ error: 'records must be an array' });
    }
    if (records.length > MAX_BATCH) {
      return res
        .status(413)
        .json({ error: `at most ${MAX_BATCH} records per batch` });
    }

    const mismatched = records.find(
      (r) => r?.whvId != null && r.whvId !== req.device.whvId,
    );
    if (mismatched) {
      return res.status(403).json({
        error: 'record whvId does not match the device token',
      });
    }

    const results = await service.ingest(records, {
      whvId: req.device.whvId,
      phcId: req.device.phcId,
    });
    res.json({ results });
  });

  app.get('/v1/acks', auth, async (req, res) => {
    const { acks, cursor } = await service.acks({
      whvId: req.device.whvId,
      since: typeof req.query.since === 'string' ? req.query.since : undefined,
    });
    res.json({ acks, cursor });
  });

  // ---- Dashboard endpoints -----------------------------------------------
  //
  // Read-only queue plus the ack write. Left unauthenticated for the hackathon
  // build and deployed on a PHC's internal network; see the note in README.md.

  app.get('/v1/queue', async (req, res) => {
    const phcId = typeof req.query.phcId === 'string' ? req.query.phcId : undefined;
    const limit = Math.min(Number(req.query.limit) || 200, 1000);
    res.json({ records: await service.queue({ phcId, limit }) });
  });

  app.post('/v1/acks', async (req, res) => {
    const result = await service.setReferralState({
      recordId: req.body?.recordId,
      referralState: req.body?.referralState,
      referralUpdatedBy: req.body?.referralUpdatedBy,
    });
    if (!result.ok) return res.status(400).json(result);
    res.json(result);
  });

  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    console.error('[arogyax-sync]', err);
    res.status(500).json({ error: 'internal error' });
  });

  return app;
}
