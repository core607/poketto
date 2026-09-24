import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { config } from "../src/config.js";
import { Models } from "../src/models.js";
import type { Usage } from "../src/types.js";

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
          mode === "valid"
            ? [
                { index: 1, relevance_score: 0.9 },
                { index: 0, relevance_score: 0.3 },
              ]
            : [
                { index: 2, relevance_score: 1 },
                { index: 2, relevance_score: 1 },
              ],
        usage: { total_tokens: 123 },
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
    assert.ok(usage[0]!.cost! > 0);
    mode = "invalid";
    await assert.rejects(
      provider.rerank("question", docs, AbortSignal.timeout(1000), record),
      /invalid candidate/,
    );
    mode = "rate";
    await assert.rejects(
      provider.rerank("question", docs, AbortSignal.timeout(1000), record),
      /429/,
    );
    assert.equal(calls, 3);
    assert.equal(usage[2]!.status, "rejected");
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});
