import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { createHash } from "node:crypto";

export const experiment = Object.freeze({
  version: "retrieval-lab-v1",
  model: "deepseek-flash",
  thinking: "disabled",
  embedding: "Qwen/Qwen3-Embedding-8B",
  reranker: "Qwen/Qwen3-Reranker-8B",
  dimensions: 4096,
  candidateDepth: 100,
  rrfK: 60,
  rerankDepth: 50,
  evidenceCount: 10,
  maxToolCalls: 12,
  commandMs: 30_000,
  investigationMs: 300_000,
  outputTokens: 4096,
  evidenceBytes: 160_000,
  requestBytes: 240_000,
  queryInstruction:
    "Given a financial question, retrieve user replies that best answer the question",
});
export const fingerprint = createHash("sha256")
  .update(JSON.stringify(experiment))
  .digest("hex");
export type Prices = {
  deepseekInput?: number;
  deepseekOutput?: number;
  embedding?: number;
  rerank?: number;
};
export function config() {
  const port = Number(process.env.LAB_PORT ?? 38470);
  if (!Number.isInteger(port) || port < 1 || port > 65535)
    throw new Error("LAB_PORT must be an integer from 1 to 65535");
  const number = (name: string) => {
    const raw = process.env[name];
    if (!raw) return undefined;
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 0)
      throw new Error(`Invalid ${name}`);
    return value;
  };
  return {
    data: resolve(process.env.LAB_DATA ?? "data"),
    port,
    python:
      process.env.LAB_PYTHON ??
      (process.platform === "win32"
        ? ".venv/Scripts/python.exe"
        : ".venv/bin/python"),
    siliconUrl:
      process.env.SILICONFLOW_BASE_URL ?? "https://api.siliconflow.cn/v1",
    deepseekUrl: process.env.DEEPSEEK_BASE_URL ?? "https://api.deepseek.com",
    siliconKey: process.env.SILICONFLOW_API_KEY,
    deepseekKey: process.env.DEEPSEEK_API_KEY,
    workerSocket:
      process.env.LAB_WORKER_SOCKET ??
      "/run/retrieval-lab-executor/control.sock",
    signingKey: process.env.LAB_SIGNING_KEY ?? "/etc/retrieval-lab/private.pem",
    exportRoot:
      process.env.LAB_EXPORT_ROOT ?? "/var/lib/retrieval-lab/pool/exports",
    prices: {
      deepseekInput: number("LAB_DEEPSEEK_INPUT_USD_PER_M"),
      deepseekOutput: number("LAB_DEEPSEEK_OUTPUT_USD_PER_M"),
      embedding: number("LAB_EMBEDDING_CNY_PER_M"),
      rerank: number("LAB_RERANK_CNY_PER_M"),
    } satisfies Prices,
  };
}
export type Config = ReturnType<typeof config>;
export function readJson<T>(path: string): T {
  return JSON.parse(readFileSync(path, "utf8")) as T;
}
export function queryInput(text: string) {
  return `Instruct: ${experiment.queryInstruction}\nQuery:${text}`;
}
