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
import type { Run, Usage } from "../src/types.js";

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

test("the first batched clarification survives restart and resumes the same task without a waiting sandbox", async () => {
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
            {
              id: "second-question",
              function: {
                name: "ask_user",
                arguments: JSON.stringify({
                  question: "Which country?",
                  options: ["US", "UK"],
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
    assert.equal(f.store.get(id).clarification!.question, "Which period?");
    f.engine.reply(id, "Recent");
    const done = await settled(f.store, id, ["completed", "failed"]);
    assert.equal(done.status, "completed");
    assert.equal(opens, 1);
    assert.match(questions[0]!, /Clarification \(Which period\?\): Recent/);
    assert.ok(!questions[0]!.includes("Which country?"));
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

test("agent executes batched calls in order and stops at evidence submission", async () => {
  const f = fixture();
  const commands: string[] = [];
  let closes = 0;
  let turns = 0;
  let active = 0;
  const call = (id: string, name: string, args: unknown) => ({
    id,
    function: { name, arguments: JSON.stringify(args) },
  });
  try {
    f.engine.models.chat = async (messages, tools) => {
      if (!tools)
        return {
          content:
            '{"answer":"Supported [a]","citations":[{"id":"a","quote":"Original source"}],"limitations":""}',
        };
      if (turns++ === 0)
        return {
          tool_calls: [
            call("first", "execute_shell", { command: "cat README.md" }),
            call("second", "execute_shell", { command: "rg interest corpus" }),
          ],
        };
      assert.deepEqual(
        messages.filter((m) => m.role === "tool").map((m) => m.tool_call_id),
        ["first", "second"],
      );
      return {
        tool_calls: [
          call("done", "submit_evidence", { ids: ["a"] }),
          call("unused", "execute_shell", { command: "must not run" }),
        ],
      };
    };
    f.engine.worker.open = async () => ({
      execute: async (command) => {
        assert.equal(++active, 1);
        commands.push(command);
        await delay(1);
        active--;
        return { stdout: command };
      },
      artifact: async () => ({}),
      close: async () => {
        closes++;
      },
    });
    const id = await f.engine.create({ qid: "dev", routes: ["agentic"] });
    const run = await settled(f.store, id, ["completed", "failed"]);
    assert.equal(run.status, "completed");
    assert.deepEqual(commands, ["cat README.md", "rg interest corpus"]);
    assert.equal(run.results.agentic!.tools, 3);
    assert.deepEqual(
      run.results.agentic!.evidence.map((d) => d.id),
      ["a"],
    );
    assert.equal(closes, 1);
  } finally {
    f.close();
  }
});

for (const { batchSize, searchReplies, skipped } of [
  { batchSize: 1, searchReplies: 11, skipped: 0 },
  { batchSize: 5, searchReplies: 15, skipped: 4 },
])
  test(`agent reserves final submission and marks only skipped searches as limited (batch ${batchSize})`, async () => {
    const f = fixture();
    let closes = 0;
    let executions = 0;
    try {
      f.engine.models.chat = async (
        messages,
        tools,
        _signal,
        _record,
        requiredTool,
      ) => {
        if (requiredTool) {
          assert.equal(requiredTool, "submit_evidence");
          assert.deepEqual(
            tools!.map((t) => t.function.name),
            ["submit_evidence"],
          );
          assert.equal(
            messages.filter((m) => m.role === "tool").length,
            searchReplies,
          );
          return {
            tool_calls: [
              {
                id: "done",
                function: { name: "submit_evidence", arguments: '{"ids":[]}' },
              },
              {
                id: "after-submission",
                function: {
                  name: "execute_shell",
                  arguments: '{"command":"must not execute after submission"}',
                },
              },
            ],
          };
        }
        return tools
          ? {
              tool_calls: Array.from({ length: batchSize }, (_, index) => ({
                id: `call-${index}`,
                function: {
                  name: "execute_shell",
                  arguments: '{"command":"rg interest corpus"}',
                },
              })),
            }
          : {
              content:
                '{"answer":"Insufficient evidence.","citations":[],"limitations":"Investigation limit."}',
            };
      };
      f.engine.worker.open = async () => ({
        execute: async () => {
          executions++;
          return { stdout: "a.md: text" };
        },
        artifact: async () => ({}),
        close: async () => {
          closes++;
        },
      });
      const id = await f.engine.create({ qid: "dev", routes: ["agentic"] });
      const run = await settled(f.store, id, ["completed", "failed"]);
      assert.equal(run.status, "completed");
      assert.equal(run.results.agentic!.tools, 12);
      assert.equal(executions, 11);
      assert.equal(
        f.store.events(id).filter((e) => e.type === "tool_skipped").length,
        skipped,
      );
      assert.equal(run.results.agentic!.limited, skipped > 0);
      assert.deepEqual(run.results.agentic!.evidence, []);
      assert.equal(closes, 1);
    } finally {
      f.close();
    }
  });

test("cancellation between batched tools prevents remaining commands and answering", async () => {
  const f = fixture();
  let closes = 0,
    executions = 0,
    id = "";
  try {
    f.engine.models.chat = async (_, tools) => {
      assert.ok(tools, "cancelled retrieval must not request an answer");
      return {
        tool_calls: ["first", "second"].map((id) => ({
          id,
          function: {
            name: "execute_shell",
            arguments: '{"command":"cat README.md"}',
          },
        })),
      };
    };
    f.engine.worker.open = async () => ({
      execute: async () => {
        executions++;
        await delay(1);
        f.engine.cancel(id);
        return { stdout: "cancelled" };
      },
      artifact: async () => ({}),
      close: async () => {
        closes++;
      },
    });
    id = await f.engine.create({ qid: "dev", routes: ["agentic"] });
    const run = await settled(f.store, id, ["cancelled", "failed"]);
    assert.equal(run.status, "cancelled");
    assert.equal(executions, 1);
    assert.equal(run.results.agentic!.tools, 1);
    assert.equal(run.results.agentic!.answer, undefined);
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

test("price changes preserve compatibility and reprice development usage without changing historical charges", async () => {
  const f = fixture();
  try {
    f.store.setMetadata("compatibility", {
      passed: true,
      fingerprint: f.engine.runFingerprint,
    });
    const usage: Usage = {
      callId: "chat",
      operation: "chat",
      model: "fake",
      input: 1_000_000,
      output: 100_000,
      milliseconds: 1,
      currency: "USD",
      cost: null,
      status: "completed",
      prices: {},
    };
    const run: Run = {
      id: "priced",
      createdAt: "2026-09-24",
      updatedAt: "",
      query: "q",
      task: "q",
      qid: "dev",
      split: "dev",
      status: "completed",
      routes: ["rag", "agentic"],
      clarify: false,
      results: {},
      usage: [usage],
      config: f.engine.snapshot,
      fingerprint: f.engine.runFingerprint,
    };
    f.store.save(run, "completed", {});
    const changed = new Engine(
      {
        ...f.settings,
        prices: { ...f.settings.prices, deepseekInput: 1, deepseekOutput: 2 },
      },
      f.store,
    );
    assert.equal(changed.runFingerprint, f.engine.runFingerprint);
    assert.deepEqual(changed.summary().compatibility, {
      passed: true,
      fingerprint: changed.runFingerprint,
    });
    assert.equal(changed.estimate().pricedPairs, 1);
    assert.equal(changed.estimate().estimated100Pairs!.USD, 120);
    assert.equal(f.store.get(run.id).usage[0]!.cost, null);
    for (let i = 0; i < 19; i++)
      f.store.save(
        {
          ...run,
          id: `unknown-${i}`,
          qid: `unknown-${i}`,
          usage: [{ ...usage, input: null }],
        },
        "completed",
        {},
      );
    assert.equal(changed.estimate().samplePairs, 20);
    await assert.rejects(changed.batch("test"), /development/);
    run.usage[0]!.status = "uncertain";
    f.store.save(run, "uncertain", {});
    assert.equal(changed.estimate().pricedPairs, 0);
    await assert.rejects(changed.batch("test"), /development/);
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
