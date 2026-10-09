"use client";

import { useRef, useState } from "react";
import { api, message } from "../lib/browser-api";

type Inspection = {
  status: "DISABLED" | "PUBLICATION_UNAVAILABLE" | "AVAILABLE";
  inspection: {
    commit: string;
    items: { articlePath: string; code: string; detail: string }[];
  } | null;
};
const labels: Record<string, string> = {
  READY: "已通过接入检查",
  INVALID_PACKAGE: "声明或文件不符合要求",
  INVALID_GAME: "规则运行失败",
  GAME_CAPACITY: "等待目录容量",
  GAME_UNAVAILABLE: "等待重新校验",
};

export function GameValidation({ workspaceId }: { workspaceId: string }) {
  const [value, setValue] = useState<Inspection | null>(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const busy = useRef(false);
  async function read() {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    setError("");
    try {
      setValue(
        await api<Inspection>(
          `/api/auth/workspaces/${encodeURIComponent(workspaceId)}/games`,
        ),
      );
    } catch (failure) {
      setError(message(failure));
    } finally {
      busy.current = false;
      setPending(false);
    }
  }
  return (
    <section className="sub-panel" aria-label="小游戏接入检查">
      <h3>小游戏接入检查</h3>
      <p>
        文章与游戏包公开后，平台会在后台检查。通过后才显示“小游戏”标签，手动填写标签不会启用游戏。
      </p>
      <button
        className="button-secondary"
        disabled={pending}
        onClick={() => void read()}
      >
        {pending ? "读取中…" : "查看校验结果"}
      </button>
      {error && <p role="alert">{error}</p>}
      {value?.status === "DISABLED" && <p>本站尚未启用小游戏运行服务。</p>}
      {value?.status === "PUBLICATION_UNAVAILABLE" && (
        <p>当前公开网站或内容快照不可用。恢复发布后才会进行检查。</p>
      )}
      {value?.inspection && (
        <>
          <p>
            以下是当前公开版本的近期结果。未列出的文章可能还在等待扫描，或尚无游戏声明；列表不是全部文章的检查报告。
          </p>
          <ul>
            {value.inspection.items.map((item) => (
              <li key={item.articlePath}>
                <strong>
                  {item.articlePath} · {labels[item.code] ?? item.code}
                </strong>
                <p>{item.detail}</p>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
