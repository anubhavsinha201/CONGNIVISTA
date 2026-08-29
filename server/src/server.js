import { MemoryRepo } from './memory_repo.js';
import { MongoRepo } from './mongo_repo.js';
import { buildApp } from './routes.js';
import { SyncService } from './service.js';

const PORT = Number(process.env.PORT ?? 8787);
const MONGO_URI = process.env.MONGO_URI ?? 'mongodb://127.0.0.1:27017';
const DB_NAME = process.env.MONGO_DB ?? 'arogyax';

/**
 * DEMO=1 runs entirely in memory.
 *
 * The stage demo must not be losable to a container that did not come up. The
 * app cannot tell the difference — same routes, same responses — so what is
 * demonstrated is the real client path either way.
 */
const DEMO = process.env.DEMO === '1';

async function main() {
  const repo = DEMO ? new MemoryRepo() : await MongoRepo.connect(MONGO_URI, DB_NAME);

  // Seed a device so a fresh checkout can sync without a provisioning flow.
  // Real deployments issue tokens per handset; this one is deliberately
  // obvious in the logs so it is never mistaken for a production credential.
  const seedToken = process.env.SEED_DEVICE_TOKEN ?? 'dev-token-whv-021';
  if (repo instanceof MemoryRepo) {
    repo.addDevice({ token: seedToken, whvId: 'whv-021', phcId: 'phc-042' });
  } else {
    await repo.devices.updateOne(
      { token: seedToken },
      {
        $set: {
          token: seedToken,
          whvId: 'whv-021',
          phcId: 'phc-042',
          provisionedAt: new Date().toISOString(),
        },
      },
      { upsert: true },
    );
  }

  const app = buildApp({ repo, service: new SyncService(repo) });

  const server = app.listen(PORT, () => {
    console.log(
      `[arogyax-sync] listening on :${PORT}  ` +
        `(${DEMO ? 'IN-MEMORY DEMO MODE' : `mongo ${MONGO_URI}/${DB_NAME}`})`,
    );
    console.log(`[arogyax-sync] seed device token: ${seedToken}`);
  });

  const shutdown = async () => {
    server.close();
    await repo.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err) => {
  console.error('[arogyax-sync] failed to start:', err);
  process.exit(1);
});
