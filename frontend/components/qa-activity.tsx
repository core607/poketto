import type { QaActivity as Activity } from "../lib/qa";

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
  if (!entries.length) return null;
  const running = entries.find((entry) => entry.state === "RUNNING");
  const thoughtCount = entries.filter(
    (entry) => entry.kind === "thinking",
  ).length;
  const toolCount = entries.length - thoughtCount;
  return (
    <div className="qa-activity" aria-label="思考与工具记录">
      <p className="qa-progress" role="status">
        {running
          ? running.kind === "thinking"
            ? "正在思考…"
            : `${toolNames[running.name] ?? running.name}…`
          : "本轮处理记录"}
      </p>
      <details className="qa-process" open={!!running}>
        <summary>
          查看处理过程 · 思考 {thoughtCount} 轮 · 工具 {toolCount} 次
        </summary>
        {entries.map((entry) => {
          const thinking = entry.kind === "thinking";
          const title = thinking
            ? "思考"
            : (toolNames[entry.name] ?? entry.name);
          const state =
            entry.state === "RUNNING"
              ? "进行中"
              : entry.state === "FAILED"
                ? "未完成"
                : "已完成";
          return (
            <details className="qa-step" key={entry.id}>
              <summary>
                <span
                  className={`qa-step-dot qa-step-${entry.state.toLowerCase()}`}
                  aria-hidden="true"
                />
                <span>{title}</span>
                <span className="qa-step-meta">
                  {state}
                  {entry.elapsedMillis > 0
                    ? ` · ${(entry.elapsedMillis / 1000).toFixed(1)} 秒`
                    : ""}
                </span>
              </summary>
              <div className="qa-step-body">
                {thinking ? (
                  <p className="qa-thinking">
                    {entry.output ||
                      (entry.state === "RUNNING"
                        ? "等待模型返回思考内容…"
                        : "这轮模型没有返回可展示的思考内容。")}
                  </p>
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
    </div>
  );
}
