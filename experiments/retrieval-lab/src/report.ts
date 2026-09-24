import type { Route, Run } from "./types.js";

export function batchReport(runs: Run[], batchId: string, cohort: string[]) {
  const attempts = runs.filter((run) => run.batchId === batchId);
  const latest = new Map<string, Run>();
  for (const run of [...attempts].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  ))
    if (run.qid && !latest.has(run.qid)) latest.set(run.qid, run);
  const routes = (["rag", "agentic"] as Route[]).map((route) => {
    let ndcg10 = 0,
      recall10 = 0,
      completed = 0,
      failed = 0,
      pending = 0,
      limited = 0,
      scored = 0;
    const retrievalMs: number[] = [],
      answerMs: number[] = [];
    for (const qid of cohort) {
      const run = latest.get(qid),
        result = run?.results[route];
      if (result?.metrics) {
        ndcg10 += result.metrics.ndcg10;
        recall10 += result.metrics.recall10;
        scored++;
      }
      if (result?.status === "completed") completed++;
      else if (!run || ["queued", "running", "waiting"].includes(run.status))
        pending++;
      else failed++;
      if (result?.limited) limited++;
      if (result?.retrievalMs !== undefined)
        retrievalMs.push(result.retrievalMs);
      if (result?.answerMs !== undefined) answerMs.push(result.answerMs);
    }
    const mean = (values: number[]) =>
      values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
    return {
      route,
      completed,
      failed,
      pending,
      limited,
      scored,
      ndcg10: ndcg10 / cohort.length,
      recall10: recall10 / cohort.length,
      meanRetrievalMs: mean(retrievalMs),
      meanAnswerMs: mean(answerMs),
      retrievalTimingSamples: retrievalMs.length,
      answerTimingSamples: answerMs.length,
    };
  });
  const knownCost = { USD: 0, CNY: 0 };
  let unknownCostCalls = 0;
  for (const run of attempts)
    for (const call of run.usage) {
      if (call.cost === null) unknownCostCalls++;
      else knownCost[call.currency] += call.cost;
    }
  return {
    batchId,
    cohortSize: cohort.length,
    scheduledQuestions: latest.size,
    attempts: attempts.length,
    provisional: routes.some((route) => route.pending > 0),
    routes,
    knownCost,
    unknownCostCalls,
    scoring:
      "Full fixed cohort denominator. Missing retrieval metrics contribute zero; generation failures retain measured retrieval metrics. Pending cohorts are provisional. Every retry charge is included.",
  };
}
