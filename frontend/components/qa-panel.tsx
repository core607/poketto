"use client";

import { useEffect, useRef, useState } from "react";
import { api, ApiError, message } from "../lib/browser-api";
import type { QaAllowance, QaChoice, QaQuestion, QaReply } from "../lib/qa";
import { LoginDialog } from "./login-dialog";

const labels: Record<string, string> = {
  DAILY_LIMIT: "今天的网页提问额度已用完，明天再来吧。",
  BUDGET_LIMIT: "全站今日模型预算已用完或正在预留中，请稍后再来。",
  QA_BUSY: "许愿井正忙，请稍后再试。",
  QA_CAPACITY: "等待补充的问题较多，请稍后再来。",
  QA_UNAVAILABLE: "站内问答暂未启用。",
  QA_NOT_FOUND: "没有找到本次请求；可以用同一个请求重试，不会重复计费。",
  QA_CONFLICT: "这次澄清已经变化，请先核对当前状态。",
};

export function QaPanel() {
  const [allowance, setAllowance] = useState<QaAllowance | null>(null);
  const [login, setLogin] = useState(false);
  const [question, setQuestion] = useState("");
  const [reply, setReply] = useState<QaReply | null>(null);
  const [choice, setChoice] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const pending = useRef<{ path: string; body: QaQuestion | QaChoice } | null>(
    null,
  );
  const inFlight = useRef(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    void refresh();
    return () => {
      alive.current = false;
    };
  }, []);

  function errorMessage(error: unknown) {
    if (error instanceof ApiError) {
      if (error.status === 401) {
        setLogin(true);
        setAllowance(null);
      }
      if (error.status === 403) {
        setAllowance(null);
        return "站内问答仅向当前创作者和管理员开放。";
      }
      if (error.code && labels[error.code]) return labels[error.code];
    }
    return message(error);
  }
  async function refresh() {
    try {
      const value = await api<QaAllowance>("/api/qa");
      if (alive.current) {
        setAllowance(value);
        setLogin(false);
      }
    } catch (error) {
      if (alive.current) setNotice(errorMessage(error));
    }
  }
  async function operation(action: () => Promise<void>) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setNotice("");
    try {
      await action();
    } catch (error) {
      if (alive.current) setNotice(errorMessage(error));
    } finally {
      inFlight.current = false;
      if (alive.current) setBusy(false);
    }
  }
  function receive(value: QaReply) {
    if (!alive.current) return;
    setReply(value);
    setChoice("");
    setUncertain(false);
    pending.current = null;
  }
  async function send() {
    const request = pending.current;
    if (!request) return;
    try {
      const value = await api<QaReply>(request.path, {
        method: "POST",
        body: request.body,
        timeoutMs: 130000,
      });
      receive(value);
      if (alive.current) await refresh();
    } catch (error) {
      if (!alive.current) return;
      if (
        !(error instanceof ApiError) ||
        error.status === 0 ||
        error.status >= 500
      ) {
        setUncertain(true);
        setNotice(
          "本次结果尚未确认。请先核对状态；不会自动重发问题或调用模型。",
        );
      } else {
        throw error;
      }
    }
  }
  async function status() {
    const id = pending.current?.body.requestId ?? reply?.requestId;
    if (id) receive(await api<QaReply>(`/api/qa/${id}`));
    await refresh();
  }
  const waiting = reply?.status === "WAITING" && reply.clarification;
  const processing = reply?.status === "RUNNING";

  return (
    <section className="community-section" aria-label="站内问答">
      <p>在公开的口袋里找线索、读文章、整理出处。当前仅创作者和管理员可用。</p>
      <p>
        问题、补充内容及检索到的公开文章会交由上游模型处理。服务器不保留完成后的问答内容，只记录用量和结果状态。
      </p>
      {login && <LoginDialog label="登录以使用问答" onLogin={refresh} />}
      {allowance && (
        <p>
          今天还可提问 {allowance.remaining} / {allowance.dailyLimit} 次。额度于{" "}
          {new Date(allowance.resetsAt).toLocaleString()} 重置。
        </p>
      )}
      {!waiting && !processing && !uncertain && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (inFlight.current) return;
            if (!question.trim() || !allowance) return;
            pending.current = {
              path: "/api/qa",
              body: {
                requestId: crypto.randomUUID(),
                question: question.trim(),
              },
            };
            setReply(null);
            void operation(send);
          }}
        >
          <label>
            想了解什么？
            <textarea
              aria-label="问题"
              value={question}
              maxLength={1000}
              onChange={(event) => setQuestion(event.target.value)}
              disabled={busy || !allowance}
            />
          </label>
          <button
            className="btn btn-primary"
            disabled={
              busy ||
              !question.trim() ||
              !allowance ||
              allowance.remaining === 0
            }
          >
            问一问
          </button>
        </form>
      )}
      {waiting && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (inFlight.current) return;
            if (!choice.trim() || !reply) return;
            pending.current = {
              path: "/api/qa/continue",
              body: {
                requestId: reply.requestId,
                revision: reply.revision,
                answer: choice.trim(),
              },
            };
            void operation(send);
          }}
        >
          <fieldset disabled={busy || uncertain}>
            <legend>{waiting.question}</legend>
            {waiting.options.map((option, index) => (
              <label key={index}>
                <input
                  type="radio"
                  name="qa-choice"
                  checked={choice === option}
                  onChange={() => setChoice(option)}
                />
                {option}
              </label>
            ))}
            <label>
              也可以自己补充
              <textarea
                aria-label="补充范围"
                value={choice}
                maxLength={500}
                onChange={(event) => setChoice(event.target.value)}
              />
            </label>
            <p>
              等待选择期间不调用模型；
              {new Date(waiting.expiresAt).toLocaleString()} 前可继续。
            </p>
            <button className="btn btn-primary" disabled={!choice.trim()}>
              按这个范围继续
            </button>
          </fieldset>
        </form>
      )}
      {(uncertain || processing || waiting) && (
        <button
          className="btn btn-secondary"
          disabled={busy}
          onClick={() => void operation(status)}
        >
          核对本次状态
        </button>
      )}
      {uncertain && (
        <button
          className="btn btn-secondary"
          disabled={busy}
          onClick={() => void operation(send)}
        >
          重试同一请求
        </button>
      )}
      {reply && (
        <div aria-label="问答结果">
          {reply.paragraphs.map((paragraph, index) => (
            <div key={index}>
              <p style={{ whiteSpace: "pre-wrap" }}>{paragraph.text}</p>
              <ul>
                {paragraph.citations.map((citation, source) => (
                  <li key={source}>
                    <a href={citation.url}>{citation.title}</a>
                    <blockquote style={{ whiteSpace: "pre-wrap" }}>
                      {citation.quote}
                    </blockquote>
                  </li>
                ))}
              </ul>
            </div>
          ))}
          <p>{reply.notice}</p>
          <p>
            模型调用 {reply.usage.calls} 次 · 输入 {reply.usage.inputTokens} /
            输出 {reply.usage.outputTokens} tokens · 按配置单价计入预算 $
            {reply.usage.costUpperUsd}
            {reply.usage.uncertain
              ? "（含结果不明调用的保守上限）"
              : "（费用上限）"}
          </p>
        </div>
      )}
      <p role="status">{busy ? "正在找线索，最多等待两分钟…" : notice}</p>
    </section>
  );
}
