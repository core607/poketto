import { config, readJson } from "../src/config.js";
import { Store } from "../src/store.js";
import { Worker, type CorpusManifest } from "../src/worker.js";
import { join } from "node:path";

const settings = config();
const store = new Store(join(settings.data, "native-acceptance.db"));
const manifest = readJson<CorpusManifest>(
  join(settings.data, "corpus-manifest.json"),
);
const worker = new Worker(settings, store, manifest);
const session = await worker.open(AbortSignal.timeout(180_000));
const command = session.execute("sleep 25");
process.stdout.write("READY_FOR_PROCESS_LOSS\n");
await command;
await session.close();
store.close();
