/** Isolated UI acceptance with synthetic model/worker replies. Never a quality benchmark. */
import { mkdtempSync, copyFileSync, linkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { config } from "../src/config.js";
import { Store } from "../src/store.js";
import { Engine } from "../src/engine.js";
import { createLab } from "../src/server.js";
import type { Json, Evidence } from "../src/types.js";

const settings = config();
const source = settings.data;
settings.data = mkdtempSync(join(tmpdir(), "retrieval-ui-fixture-"));
settings.port = 38471;
settings.deepseekKey = "synthetic-fixture";
settings.siliconKey = "synthetic-fixture";
linkSync(join(source, "fiqa.db"), join(settings.data, "fiqa.db"));
for (const name of [
  "corpus-manifest.json",
  "audit.json",
  "calibration.json",
  "splits.json",
  "dev.trec",
  "test.trec",
])
  copyFileSync(join(source, name), join(settings.data, name));
const store = new Store(join(settings.data, "runs.db"));
const engine = new Engine(settings, store);
const summary = engine.summary.bind(engine);
engine.summary = () => ({ ...summary(), fixture: true });
store.setMetadata("compatibility", {
  passed: true,
  fingerprint: engine.runFingerprint,
  synthetic: true,
});
engine.models.embed = async () => [1, ...Array(4095).fill(0)];
engine.models.rerank = async (_, candidates) =>
  candidates.slice(0, 10).map((row) => ({ ...row, score: 1 }));
engine.worker.open = async () => ({
  execute: async () => ({
    stdout: "SYNTHETIC UI FIXTURE: directory navigation shown",
    exitCode: 0,
  }),
  artifact: async () => ({}),
  close: async () => {},
});
engine.models.chat = async (messages, tools) => {
  const call = (name: string, args: object) => ({
    tool_calls: [
      {
        id: "fixture-call",
        type: "function",
        function: { name, arguments: JSON.stringify(args) },
      },
    ],
  });
  if (tools?.[0]?.function.name === "ask_user")
    return call("ask_user", {
      question: "[UI fixture] Which scope should both routes use?",
      options: ["Recent evidence", "All available evidence"],
    });
  if (tools) {
    if (!messages.some((m) => m.role === "tool"))
      return call("execute_shell", { command: "cat README.md" });
    return call("submit_evidence", { ids: ["3"] });
  }
  const input = JSON.parse(messages[1]!.content) as { evidence: Evidence[] };
  const doc = input.evidence[0]!;
  return {
    content: JSON.stringify({
      answer: `Synthetic UI acceptance result [${doc.id}]. This is not a model-quality finding.`,
      citations: [{ id: doc.id, quote: doc.text.slice(0, 100) }],
      limitations:
        "Synthetic model and worker replies; original document lookup and application lifecycle are real.",
    }),
  } as Json;
};
const server = createLab(engine, store, settings);
server.listen(settings.port, "127.0.0.1", () =>
  console.log(`SYNTHETIC UI FIXTURE ONLY: http://127.0.0.1:${settings.port}`),
);
function stop() {
  server.closeAllConnections();
  server.close();
  engine.data.stop();
  store.close();
}
process.on("SIGTERM", stop);
process.on("SIGINT", stop);
