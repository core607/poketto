import type { QaActivity as Activity } from "../lib/qa";
import { toolSummary } from "../lib/qa-activity-summary";

const toolNames: Record<string, string> = {
  search: "搜索公开文章",
  read: "阅读文章",
  clarify: "确认问题范围",
  answer: "整理答案与出处",
};

function pretty(text: string) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

export function QaActivity({ entries }: { entries: Activity[] }) {
  const running = entries.find((entry) => entry.state === "RUNNING");
  const visible = entries.filter(
    (entry) => entry.kind !== "thinking" || entry.output.trim().length > 0,
  );
  if (!visible.length && !running) return null;
  const thoughtCount = visible.filter(
    (entry) => entry.kind === "thinking" && entry.state === "COMPLETED",
  ).length;
  const toolCount = visible.filter((entry) => entry.kind === "tool").length;
  return (
    <div className="qa-activity" aria-label="思考与工具记录">
      <p className="qa-progress" role="status">
        {running
          ? running.kind === "thinking"
            ? "正在处理…"
            : `${toolNames[running.name] ?? running.name}…`
          : "本轮处理记录"}
      </p>
      {visible.length > 0 && (
        <details className="qa-process" open={!!running}>
          <summary>
            查看处理过程
            {thoughtCount > 0 && ` · 思考 ${thoughtCount} 轮`}
            {toolCount > 0 && ` · 工具 ${toolCount} 次`}
          </summary>
          {visible.map((entry) => {
            const thinking = entry.kind === "thinking";
            const title = thinking
              ? entry.state === "FAILED"
                ? "模型调用"
                : "思考"
              : toolSummary(entry, toolNames[entry.name] ?? entry.name);
            const state =
              entry.state === "RUNNING"
                ? "进行中"
                : entry.state === "FAILED"
                  ? "未完成"
                  : "已完成";
            return (
              <details
                className={`qa-step${thinking ? " qa-thought" : ""}`}
                key={entry.id}
              >
                <summary>
                  <span
                    className={`qa-step-dot qa-step-${entry.state.toLowerCase()}`}
                    aria-hidden="true"
                  />
                  <span className="qa-step-title">{title}</span>
                  <span className="qa-step-meta">
                    <span>{state}</span>
                    <span className="qa-step-time">
                      {entry.state === "RUNNING"
                        ? ""
                        : entry.elapsedMillis < 100
                          ? "<0.1 秒"
                          : `${(entry.elapsedMillis / 1000).toFixed(1)} 秒`}
                    </span>
                  </span>
                </summary>
                <div className="qa-step-body">
                  {thinking ? (
                    <p className="qa-thinking">{entry.output}</p>
                  ) : (
                    <>
                      <p>调用 · {entry.name}</p>
                      <pre>{pretty(entry.input)}</pre>
                      {entry.output && (
                        <>
                          <p>{entry.state === "FAILED" ? "错误" : "结果"}</p>
                          <pre>{pretty(entry.output)}</pre>
                        </>
                      )}
                    </>
                  )}
                </div>
              </details>
            );
          })}
        </details>
      )}
    </div>
  );
}
