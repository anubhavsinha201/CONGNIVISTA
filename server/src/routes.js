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

/**
 * Origins allowed to call this server from a browser.
 *
 * The dashboard endpoints below are unauthenticated (see README "Known gaps"),
 * which is defensible on a PHC LAN. `Access-Control-Allow-Origin: *` is not: it
 * turns "reachable from the clinic network" into "reachable from any page anyone
 * on that network happens to open", and `POST /v1/acks` is a write. The two are
 * only safe together if the origin is actually constrained, so the default is the
 * dashboard's own dev origin rather than a wildcard.
 *
 * Set DASHBOARD_ORIGIN to the real dashboard origin in a deployment. '*' still
 * works, but now only when someone has typed it.
 */
export const DEFAULT_DASHBOARD_ORIGINS = [
  'http://localhost:8080',
  'http://127.0.0.1:8080',
];

export function buildApp({ repo, service, dashboardOrigin = DEFAULT_DASHBOARD_ORIGINS }) {
  const app = express();
  app.use(express.json({ limit: '2mb' }));

  const allowed = Array.isArray(dashboardOrigin) ? dashboardOrigin : [dashboardOrigin];
  const allowAny = allowed.includes('*');

  app.use((req, res, next) => {
    const origin = req.get('origin');
    if (allowAny) {
      res.set('Access-Control-Allow-Origin', '*');
    } else if (origin && allowed.includes(origin)) {
      res.set('Access-Control-Allow-Origin', origin);
      // The response varies by Origin now, so it must not be cached under one key.
      res.set('Vary', 'Origin');
    }
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
    const body = req.body ?? {};
    const args = {
      recordId: body.recordId,
      referralState: body.referralState,
      referralUpdatedBy: body.referralUpdatedBy,
    };
    // Forward clinicianOutcome only when the request actually names the key -
    // `'clinicianOutcome' in body` rather than `body.clinicianOutcome`, so a
    // plain referral-state update (body has no such key at all) reaches the
    // service as `undefined`, which SyncService.setReferralState treats as
    // "leave the outcome alone" rather than "clear it".
    if ('clinicianOutcome' in body) args.clinicianOutcome = body.clinicianOutcome;

    const result = await service.setReferralState(args);
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
