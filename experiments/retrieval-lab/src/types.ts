export type Route = "rag" | "agentic";
export type Status =
  | "queued"
  | "running"
  | "waiting"
  | "completed"
  | "failed"
  | "cancelled"
  | "interrupted";
export type Evidence = {
  id: string;
  text: string;
  path?: string;
  score?: number;
};
export type Usage = {
  callId: string;
  route?: Route | "clarification";
  operation: string;
  model: string;
  input: number | null;
  output: number | null;
  milliseconds: number;
  currency: "USD" | "CNY";
  cost: number | null;
  status: "completed" | "uncertain" | "rejected";
  prices: unknown;
};
export type Answer = {
  answer: string;
  citations: { id: string; quote: string }[];
  limitations: string;
};
export type Result = {
  route: Route;
  status: Status;
  evidence: Evidence[];
  answer?: Answer;
  metrics?: { ndcg10: number; recall10: number };
  retrievalMs?: number;
  answerMs?: number;
  tools: number;
  limited: boolean;
  error?: string;
};
export type Run = {
  id: string;
  createdAt: string;
  updatedAt: string;
  status: Status;
  query: string;
  task: string;
  qid?: string;
  split?: "dev" | "test";
  batchId?: string;
  routes: Route[];
  clarify: boolean;
  clarification?: { question: string; options: string[] };
  clarificationReply?: string;
  results: Partial<Record<Route, Result>>;
  usage: Usage[];
  config: unknown;
  fingerprint: string;
  retryOf?: string;
  error?: string;
};
export type Event = {
  seq: number;
  runId: string;
  time: string;
  type: string;
  data: unknown;
};
export type Json = Record<string, any>;
