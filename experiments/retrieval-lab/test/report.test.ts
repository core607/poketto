import { test } from "node:test";
import assert from "node:assert/strict";
import { batchReport } from "../src/report.js";
import type { Run } from "../src/types.js";

test("batch scores retain failed questions and all retry charges", () => {
  const old = {
    id: "old",
    qid: "one",
    batchId: "batch",
    createdAt: "2026-01-01",
    status: "failed",
    results: {},
    usage: [{ cost: null, currency: "USD" }],
  } as Run;
  const latest = {
    id: "new",
    qid: "one",
    batchId: "batch",
    createdAt: "2026-01-02",
    status: "completed",
    results: {
      rag: { status: "completed", metrics: { ndcg10: 1, recall10: 0.5 } },
    },
    usage: [{ cost: 0.1, currency: "USD" }],
  } as Run;
  const failed = {
    id: "two",
    qid: "two",
    batchId: "batch",
    createdAt: "2026-01-03",
    status: "failed",
    results: {},
    usage: [],
  } as unknown as Run;
  const report = batchReport([old, latest, failed], "batch", ["one", "two"]);
  assert.equal(report.routes[0]!.ndcg10, 0.5);
  assert.equal(report.routes[0]!.recall10, 0.25);
  assert.equal(report.routes[0]!.failed, 1);
  assert.equal(report.scheduledQuestions, 2);
  assert.equal(report.knownCost.USD, 0.1);
  assert.equal(report.unknownCostCalls, 1);
});
