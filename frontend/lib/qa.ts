export type QaAllowance = {
  remaining: number;
  dailyLimit: number;
  resetsAt: string;
  defaultProvider: string;
  models: {
    provider: string;
    model: string;
    configured: boolean;
    runCostUpperUsd: string;
  }[];
  anthropicBudget: {
    limitUsd: string;
    spentUsd: string;
    reservedUsd: string;
    remainingUsd: string;
    resetsAt: string;
  };
};
export type QaQuestion = {
  requestId: string;
  question: string;
  provider: string;
};
export type QaChoice = { requestId: string; revision: number; answer: string };
export type QaReply = {
  requestId: string;
  status: "RUNNING" | "WAITING" | "COMPLETED" | "FAILED";
  code: string;
  revision: number;
  paragraphs: {
    text: string;
    citations: {
      reference: string;
      title: string;
      url: string;
      quote: string;
    }[];
  }[];
  clarification?: {
    question: string;
    options: string[];
    expiresAt: string;
  } | null;
  notice: string;
  selection: {
    requestedProvider: string;
    provider: string;
    model: string;
    fallbackReason: string | null;
  };
  activity: QaActivity[];
  usage: {
    calls: number;
    inputTokens: number;
    outputTokens: number;
    costUpperUsd: string;
    uncertain: boolean;
  };
};

export type QaActivity = {
  id: number;
  kind: string;
  name: string;
  state: "RUNNING" | "COMPLETED" | "FAILED";
  input: string;
  output: string;
  elapsedMillis: number;
};
