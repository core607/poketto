import { experiment, queryInput, type Config } from "./config.js";
import type { Evidence, Json, Usage } from "./types.js";
import { randomUUID } from "node:crypto";

export type RecordCall = (type: string, data: unknown) => void;
export class Models {
  constructor(private config: Config) {}
  async request(
    kind: "chat" | "embed" | "rerank",
    payload: Json,
    signal: AbortSignal,
    record: RecordCall,
  ): Promise<Json> {
    signal.throwIfAborted();
    const deepseek = kind === "chat";
    const key = deepseek ? this.config.deepseekKey : this.config.siliconKey;
    if (!key)
      throw new Error(
        `${deepseek ? "DeepSeek" : "SiliconFlow"} key is not configured`,
      );
    const base = deepseek ? this.config.deepseekUrl : this.config.siliconUrl;
    const suffix = {
      chat: "/chat/completions",
      embed: "/embeddings",
      rerank: "/rerank",
    }[kind];
    const started = performance.now();
    const usage: Usage = {
      callId: randomUUID(),
      operation: kind,
      model: payload.model,
      input: null,
      output: null,
      milliseconds: 0,
      currency: deepseek ? "USD" : "CNY",
      cost: null,
      status: "uncertain",
      prices: this.config.prices,
    };
    record("provider_started", {
      ...usage,
      requestBytes: Buffer.byteLength(JSON.stringify(payload)),
    });
    try {
      const response = await fetch(base.replace(/\/$/, "") + suffix, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${key}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
        signal: AbortSignal.any([signal, AbortSignal.timeout(120_000)]),
      });
      if (!response.ok) {
        if (response.status >= 400 && response.status < 500)
          usage.status = "rejected";
        throw new Error(
          `Provider ${kind} returned HTTP ${response.status}; no automatic retry`,
        );
      }
      const body = (await response.json()) as Json;
      usage.status = "completed";
      usage.model = typeof body.model === "string" ? body.model : payload.model;
      const raw = body.usage ?? {};
      usage.input = Number.isFinite(raw.prompt_tokens ?? raw.total_tokens)
        ? (raw.prompt_tokens ?? raw.total_tokens)
        : null;
      usage.output = Number.isFinite(raw.completion_tokens)
        ? raw.completion_tokens
        : deepseek
          ? null
          : 0;
      const prices = this.config.prices;
      const inputPrice =
        kind === "chat"
          ? prices.deepseekInput
          : kind === "embed"
            ? prices.embedding
            : prices.rerank;
      const outputPrice = kind === "chat" ? prices.deepseekOutput : 0;
      if (
        usage.input !== null &&
        usage.output !== null &&
        inputPrice !== undefined &&
        outputPrice !== undefined
      )
        usage.cost =
          (usage.input * inputPrice + usage.output * outputPrice) / 1e6;
      return body;
    } finally {
      usage.milliseconds = performance.now() - started;
      record("provider_finished", usage);
    }
  }
  async chat(
    messages: Json[],
    tools: Json[] | undefined,
    signal: AbortSignal,
    record: RecordCall,
  ) {
    if (Buffer.byteLength(JSON.stringify(messages)) > experiment.requestBytes)
      throw new Error("Model context byte bound exceeded");
    const body = await this.request(
      "chat",
      {
        model: experiment.model,
        messages,
        tools,
        ...(tools
          ? { parallel_tool_calls: false }
          : { response_format: { type: "json_object" } }),
        thinking: { type: experiment.thinking },
        max_tokens: experiment.outputTokens,
        temperature: 0,
      },
      signal,
      record,
    );
    const choice = body.choices?.[0];
    if (!choice?.message || choice.finish_reason === "length")
      throw new Error("Model response missing or output limit reached");
    return choice.message as Json;
  }
  async embed(text: string, signal: AbortSignal, record: RecordCall) {
    const body = await this.request(
      "embed",
      {
        model: experiment.embedding,
        input: [queryInput(text)],
        dimensions: experiment.dimensions,
        encoding_format: "float",
      },
      signal,
      record,
    );
    const vector = body.data?.[0]?.embedding;
    if (
      !Array.isArray(vector) ||
      vector.length !== experiment.dimensions ||
      vector.some((x) => !Number.isFinite(x))
    )
      throw new Error("Embedding response has invalid dimensions or values");
    const norm = Math.hypot(...vector);
    if (!norm) throw new Error("Embedding response is zero");
    return vector.map((v) => v / norm);
  }
  async rerank(
    query: string,
    candidates: Evidence[],
    signal: AbortSignal,
    record: RecordCall,
  ) {
    if (candidates.some((d) => Buffer.byteLength(d.text + query) > 30_000))
      throw new Error("Reranker pair exceeds conservative context bound");
    const body = await this.request(
      "rerank",
      {
        model: experiment.reranker,
        query,
        documents: candidates.map((d) => d.text),
        top_n: Math.min(experiment.evidenceCount, candidates.length),
        return_documents: false,
      },
      signal,
      record,
    );
    if (
      !Array.isArray(body.results) ||
      body.results.length !==
        Math.min(experiment.evidenceCount, candidates.length)
    )
      throw new Error("Reranker returned an incomplete result");
    const seen = new Set<number>();
    return body.results.map((row: Json) => {
      if (
        !Number.isInteger(row.index) ||
        !candidates[row.index] ||
        seen.has(row.index) ||
        !Number.isFinite(row.relevance_score)
      )
        throw new Error("Reranker returned invalid candidate positions");
      seen.add(row.index);
      return { ...candidates[row.index]!, score: row.relevance_score };
    });
  }
}
