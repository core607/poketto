import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { config } from "../src/config.js";
import { Models } from "../src/models.js";
import type { Json, Usage } from "../src/types.js";

test("final retrieval submission uses the provider's named tool choice", async () => {
  let payload: Json = {};
  const server = createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(chunk);
    payload = JSON.parse(Buffer.concat(chunks).toString());
    res.setHeader("Content-Type", "application/json");
    res.end(
      JSON.stringify({
        choices: [
          {
            finish_reason: "tool_calls",
            message: {
              tool_calls: [
                {
                  id: "done",
                  function: {
                    name: "submit_evidence",
                    arguments: '{"ids":[]}',
                  },
                },
              ],
            },
          },
        ],
        usage: { prompt_tokens: 10, completion_tokens: 5 },
      }),
    );
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const provider = new Models({
      ...config(),
      deepseekKey: "test",
      deepseekUrl: `http://127.0.0.1:${(server.address() as { port: number }).port}`,
    });
    await provider.chat(
      [{ role: "user", content: "Select evidence" }],
      [{ type: "function", function: { name: "submit_evidence" } }],
      AbortSignal.timeout(1000),
      () => {},
      "submit_evidence",
    );
    assert.deepEqual(payload.tool_choice, {
      type: "function",
      function: { name: "submit_evidence" },
    });
    assert.equal(payload.thinking.type, "disabled");
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("reranker indices map to original candidate order and rate limits are not retried", async () => {
  let calls = 0,
    mode = "valid";
  const server = createServer((req, res) => {
    calls++;
    req.resume();
    res.setHeader("Content-Type", "application/json");
    if (mode === "rate") {
      res.writeHead(429);
      res.end("{}");
      return;
    }
    res.end(
      JSON.stringify({
        results:
          mode !== "invalid"
            ? [
                { index: 1, relevance_score: 0.9 },
                { index: 0, relevance_score: 0.3 },
              ]
            : [
                { index: 2, relevance_score: 1 },
                { index: 2, relevance_score: 1 },
              ],
        ...(mode === "missing-usage"
          ? {}
          : mode === "partial-meta"
            ? {
                meta: { tokens: { output_tokens: 0 } },
                usage: { prompt_tokens: 123 },
              }
            : { meta: { tokens: { input_tokens: 123, output_tokens: 0 } } }),
      }),
    );
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const provider = new Models({
    ...config(),
    siliconKey: "test",
    siliconUrl: `http://127.0.0.1:${(server.address() as { port: number }).port}`,
    prices: { ...config().prices, rerank: 0.7 },
  });
  const usage: Usage[] = [];
  const record = (type: string, value: unknown) => {
    if (type === "provider_finished") usage.push(value as Usage);
  };
  try {
    const docs = [
      { id: "a", text: "first" },
      { id: "b", text: "second" },
    ];
    const rows = await provider.rerank(
      "question",
      docs,
      AbortSignal.timeout(1000),
      record,
    );
    assert.deepEqual(
      rows.map((r) => r.id),
      ["b", "a"],
    );
    assert.equal(usage[0]!.input, 123);
    assert.equal(usage[0]!.output, 0);
    assert.equal(usage[0]!.cost, (123 * 0.7) / 1e6);
    mode = "invalid";
    await assert.rejects(
      provider.rerank("question", docs, AbortSignal.timeout(1000), record),
      /invalid candidate/,
    );
    mode = "missing-usage";
    await provider.rerank("question", docs, AbortSignal.timeout(1000), record);
    assert.equal(usage[2]!.input, null);
    assert.equal(usage[2]!.cost, null);
    mode = "partial-meta";
    await provider.rerank("question", docs, AbortSignal.timeout(1000), record);
    assert.equal(usage[3]!.input, 123);
    assert.equal(usage[3]!.cost, (123 * 0.7) / 1e6);
    mode = "rate";
    await assert.rejects(
      provider.rerank("question", docs, AbortSignal.timeout(1000), record),
      /429/,
    );
    assert.equal(calls, 5);
    assert.equal(usage[4]!.status, "rejected");
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});
