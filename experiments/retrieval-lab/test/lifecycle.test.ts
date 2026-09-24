import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { config, queryInput } from "../src/config.js";
import { Store } from "../src/store.js";
import { Engine, validateAnswer } from "../src/engine.js";
import { createLab } from "../src/server.js";
import { request } from "node:http";
import type { Run } from "../src/types.js";

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), "retrieval-test-"));
  for (const [name, value] of Object.entries({
    "corpus-manifest.json": { documents: 2, commit: "a".repeat(40) },
    "splits.json": { dev: ["dev"], test: ["test"], humanReview: ["test"] },
    "audit.json": { documents: 2, databaseSha256: "b".repeat(64), pins: {} },
    "calibration.json": { passed: true },
  }))
    writeFileSync(join(directory, name), JSON.stringify(value));
  const settings = { ...config(), data: directory };
  const store = new Store(join(directory, "runs.db"));
  const engine = new Engine(settings, store);
  engine.data.query = async (qid) =>
    qid === "dev" ? "A financial question" : "Another question";
  engine.data.documents = async (ids) =>
    ids.map((id) => ({ id, text: "Original source evidence." }));
  engine.data.call = async () => ({ ndcg10: 1, recall10: 1 }) as any;
  return {
    settings,
    store,
    engine,
    close() {
      engine.data.stop();
      store.close();
      rmSync(directory, { recursive: true, force: true });
    },
  };
}
async function settled(
  store: Store,
  id: string,
  statuses = ["waiting", "completed", "failed", "cancelled"],
) {
  for (let i = 0; i < 200; i++) {
    const run = store.get(id);
    if (statuses.includes(run.status)) return run;
    await delay(10);
  }
  throw new Error("Run failed to reach expected durable state");
}

test("clarification survives restart, starts no sandbox, and resumes the same task", async () => {
  const f = fixture();
  let opens = 0;
  const questions: string[] = [];
  try {
    f.engine.models.chat = async (messages, tools) => {
      if (tools?.[0]?.function.name === "ask_user")
        return {
          tool_calls: [
            {
              id: "ask",
              function: {
                name: "ask_user",
                arguments: JSON.stringify({
                  question: "Which period?",
                  options: ["Recent", "All time"],
                }),
              },
            },
          ],
        };
      if (tools) {
        questions.push(messages[1]!.content);
        return {
          tool_calls: [
            {
              id: "done",
              function: { name: "submit_evidence", arguments: '{"ids":["a"]}' },
            },
          ],
        };
      }
      return {
        content: JSON.stringify({
          answer: "Supported [a]",
          citations: [{ id: "a", quote: "Original source" }],
          limitations: "No exhaustive count.",
        }),
      };
    };
    f.engine.worker.open = async () => {
      opens++;
      return {
        execute: async () => ({}),
        artifact: async () => ({}),
        close: async () => {},
      };
    };
    const id = await f.engine.create({
      query: "Find evidence",
      routes: ["agentic"],
    });
    await settled(f.store, id);
    assert.equal(opens, 0);
    f.store.recover();
    assert.equal(f.store.get(id).status, "waiting");
    f.engine.reply(id, "Recent");
    const done = await settled(f.store, id, ["completed", "failed"]);
    assert.equal(done.status, "completed");
    assert.equal(opens, 1);
    assert.match(questions[0]!, /Clarification \(Which period\?\): Recent/);
  } finally {
    f.close();
  }
});

test("restart marks uncertain provider work and does not silently replay queued work", () => {
  const f = fixture();
  try {
    const run = {
      id: "run",
      createdAt: "2026-09-24",
      updatedAt: "",
      query: "q",
      task: "q",
      status: "running",
      routes: ["rag"],
      clarify: false,
      results: {},
      usage: [],
      config: {},
      fingerprint: "test",
    } as Run;
    f.store.save(run, "provider_started", {
      callId: "paid-call",
      operation: "chat",
      model: "fake",
      status: "uncertain",
      cost: null,
    });
    f.store.recover();
    const recovered = f.store.get("run");
    assert.equal(recovered.status, "interrupted");
    assert.equal(recovered.usage.length, 1);
    assert.equal(recovered.usage[0]!.cost, null);
    f.store.recover();
    assert.equal(f.store.get("run").usage.length, 1);
  } finally {
    f.close();
  }
});

test("agent tool cap is reported without treating every observed file as retrieved evidence", async () => {
  const f = fixture();
  let closes = 0;
  try {
    f.engine.models.chat = async (_, tools) =>
      tools
        ? {
            tool_calls: [
              {
                id: "call",
                function: {
                  name: "execute_shell",
                  arguments: '{"command":"rg interest corpus"}',
                },
              },
            ],
          }
        : {
            content:
              '{"answer":"Insufficient evidence.","citations":[],"limitations":"Investigation limit."}',
          };
    f.engine.worker.open = async () => ({
      execute: async () => ({ stdout: "a.md: text" }),
      artifact: async () => ({}),
      close: async () => {
        closes++;
      },
    });
    const id = await f.engine.create({ qid: "dev", routes: ["agentic"] });
    const run = await settled(f.store, id, ["completed", "failed"]);
    assert.equal(run.status, "completed");
    assert.equal(run.results.agentic!.tools, 12);
    assert.equal(run.results.agentic!.limited, true);
    assert.deepEqual(run.results.agentic!.evidence, []);
    assert.equal(closes, 1);
  } finally {
    f.close();
  }
});

test("cancellation during a model wait closes the sandbox and prevents answering", async () => {
  const f = fixture();
  let closes = 0;
  try {
    f.engine.worker.open = async () => ({
      execute: async () => ({}),
      artifact: async () => ({}),
      close: async () => {
        closes++;
      },
    });
    f.engine.models.chat = async (_, __, signal) =>
      new Promise((_, reject) =>
        signal.addEventListener("abort", () => reject(signal.reason), {
          once: true,
        }),
      );
    const id = await f.engine.create({ qid: "dev", routes: ["agentic"] });
    await delay(20);
    f.engine.cancel(id);
    const run = await settled(f.store, id, ["cancelled"]);
    assert.equal(run.results.agentic!.answer, undefined);
    assert.equal(closes, 1);
  } finally {
    f.close();
  }
});

test("stop-all prevents an in-flight batch query lookup from enqueuing new work", async () => {
  const f = fixture();
  let release: (text: string) => void = () => {};
  f.engine.data.query = () =>
    new Promise<string>((resolve) => {
      release = resolve;
    });
  try {
    const batch = f.engine.batch("dev");
    f.engine.cancelAll();
    release("Question");
    await assert.rejects(batch, /cancelled/);
    assert.deepEqual(f.store.list(), []);
  } finally {
    f.close();
  }
});

test("citations must refer to selected original text, not mutated sandbox content", () => {
  const original = [{ id: "a", text: "Actual source." }];
  assert.throws(() =>
    validateAnswer(
      {
        answer: "a",
        citations: [{ id: "b", quote: "Actual" }],
        limitations: "",
      },
      original,
    ),
  );
  assert.throws(() =>
    validateAnswer(
      {
        answer: "a",
        citations: [{ id: "a", quote: "Forged source" }],
        limitations: "",
      },
      original,
    ),
  );
  assert.equal(
    validateAnswer(
      {
        answer: "a",
        citations: [{ id: "a", quote: "Actual source." }],
        limitations: "",
      },
      original,
    ).citations.length,
    1,
  );
  assert.equal(
    queryInput("Why?"),
    "Instruct: Given a financial question, retrieve user replies that best answer the question\nQuery:Why?",
  );
});

test("HTTP writes reject cross-origin requests and expose no provider configuration secrets", async () => {
  const f = fixture();
  f.settings.siliconKey = "private-sentinel";
  const server = createLab(f.engine, f.store, f.settings);
  try {
    await new Promise<void>((resolve) =>
      server.listen(0, "127.0.0.1", resolve),
    );
    f.settings.port = (server.address() as { port: number }).port;
    const base = `http://127.0.0.1:${f.settings.port}`;
    const rejected = await fetch(base + "/api/runs", {
      method: "POST",
      headers: {
        Origin: "https://example.invalid",
        "Content-Type": "application/json",
      },
      body: "{}",
    });
    assert.equal(rejected.status, 403);
    const status = await (await fetch(base + "/api/status")).text();
    assert.ok(!status.includes("private-sentinel"));
    const rebind = await new Promise<number>((resolve) => {
      const req = request(
        base + "/api/status",
        { headers: { Host: "attacker.invalid" } },
        (res) => {
          res.resume();
          resolve(res.statusCode!);
        },
      );
      req.end();
    });
    assert.equal(rebind, 403);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    f.close();
  }
});
