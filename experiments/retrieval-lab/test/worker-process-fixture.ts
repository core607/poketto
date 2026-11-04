import { config, readJson } from "../src/config.js";
import { Store } from "../src/store.js";
import { Worker, type CorpusManifest } from "../src/worker.js";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { Sandcrab, SandcrabError } from "../src/sandcrab.js";
import type { Json } from "../src/types.js";

const settings = config();
const store = new Store(join(settings.data, "native-acceptance.db"));
const manifest = readJson<CorpusManifest>(
  join(settings.data, "corpus-manifest.json"),
);
const api = new Sandcrab(settings.workerSocket);
const worker = new Worker(settings, store, manifest, api);
const session = await worker.open(AbortSignal.timeout(180_000));
let failure: unknown;
const command = session.execute("sleep 25").catch((error) => {
  failure = error;
});
const observed = store.metadata<Json[]>("sandboxCopies")![0]!;
const runId = observed.runs.at(-1);
const deadline = Date.now() + 20_000;
while (true) {
  if (failure) throw failure;
  if (Date.now() >= deadline)
    throw new Error("The actual native command was not observed");
  try {
    const run = await api.json("GET", `/v1/runs/${runId}`);
    if (
      run.id === runId &&
      run.sessionId === observed.sessionId &&
      run.state === "running"
    )
      break;
    throw new Error("Process-loss command ended before observation");
  } catch (error) {
    if (!(error instanceof SandcrabError && error.code === "RUN_NOT_FOUND"))
      throw error;
  }
  await delay(20);
}
process.stdout.write("READY_FOR_PROCESS_LOSS\n");
await command;
if (failure) throw failure;
await session.close();
store.close();
