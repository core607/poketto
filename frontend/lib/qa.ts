export type QaAllowance = {
  remaining: number;
  dailyLimit: number;
  resetsAt: string;
  runCostUpperUsd: string;
};
export type QaQuestion = { requestId: string; question: string };
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
  usage: {
    calls: number;
    inputTokens: number;
    outputTokens: number;
    costUpperUsd: string;
    uncertain: boolean;
  };
};
