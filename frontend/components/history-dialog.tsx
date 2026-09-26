"use client";
import { useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useWorkspaceApi } from "./workspace-context";
import { useConfirmation } from "./confirmation";
import { sourceDifference } from "../lib/source-diff";
import { message } from "../lib/browser-api";
import type { RepositoryFile } from "../lib/types";
import { SourceDiff } from "./source-diff";
import { useModal } from "./use-modal";

type HistoryEntry = {
  commit: string;
  subject: string;
  author: string;
  committedAt: string;
  present: boolean;
};
type HistoryPage = {
  commit: string | null;
  path: string;
  entries: HistoryEntry[];
  nextOffset: number | null;
};

/** Shows each line ending, so CRLF and a missing final newline are visible differences. */
function lineEnding(text: string) {
  if (text.endsWith("\r\n")) return text.slice(0, -2) + " ⟪CRLF⟫";
  if (text.endsWith("\n")) return text.slice(0, -1);
  return text + " ⟪无行尾换行⟫";
}

export function HistoryDialog({
  file,
  currentSource,
  dirty,
  writable,
  returnFocus,
  onRestore,
  onClose,
}: {
  file: RepositoryFile;
  currentSource: string;
  dirty: boolean;
  writable: boolean;
  returnFocus: HTMLElement | null;
  onRestore: (source: string, commit: string) => void;
  onClose: () => void;
}) {
  const api = useWorkspaceApi();
  const confirm = useConfirmation();
  const title = useId();
  const dialog = useModal(returnFocus);
  const alive = useRef(false);
  const selection = useRef(0);
  const pagePending = useRef(false);
  const [page, setPage] = useState<HistoryPage | null>(null);
  const [older, setOlder] = useState(false);
  const [busy, setBusy] = useState(false);
  const [reading, setReading] = useState(false);
  const [error, setError] = useState("");
  const [historical, setHistorical] = useState<RepositoryFile | null>(null);
  const [selected, setSelected] = useState("");
  const [restoring, setRestoring] = useState(false);
  const difference = useMemo(
    () =>
      historical?.source === null || historical === null
        ? null
        : sourceDifference(historical.source, currentSource),
    [historical, currentSource],
  );

  async function load(previous: HistoryPage | null = null) {
    if (pagePending.current) return;
    pagePending.current = true;
    setBusy(true);
    setError("");
    try {
      const query = new URLSearchParams({ path: file.path, limit: "20" });
      if (previous?.commit && previous.nextOffset !== null) {
        query.set("commit", previous.commit);
        query.set("offset", String(previous.nextOffset));
      }
      const result = await api<HistoryPage>(
        "/api/admin/repository/history?" + query,
      );
      if (alive.current) {
        setPage(result);
        setOlder(previous !== null);
      }
    } catch (failure) {
      if (alive.current) setError(message(failure));
    } finally {
      pagePending.current = false;
      if (alive.current) setBusy(false);
    }
  }

  useLayoutEffect(() => {
    alive.current = true;
    void load();
    return () => {
      alive.current = false;
      selection.current++;
    };
  }, []);

  async function select(entry: HistoryEntry) {
    const request = ++selection.current;
    setSelected(entry.commit);
    setHistorical(null);
    setReading(true);
    setError("");
    try {
      const result = await api<RepositoryFile>(
        "/api/admin/repository/file?" +
          new URLSearchParams({ path: file.path, commit: entry.commit }),
      );
      if (alive.current && selection.current === request) setHistorical(result);
    } catch (failure) {
      if (alive.current && selection.current === request)
        setError(message(failure));
    } finally {
      if (alive.current && selection.current === request) setReading(false);
    }
  }

  async function restore() {
    if (
      restoring ||
      !writable ||
      historical?.source == null ||
      !historical.commit
    )
      return;
    const candidate = historical;
    const request = selection.current;
    setRestoring(true);
    if (
      dirty &&
      !(await confirm({
        title: "用历史版本替换当前编辑内容？",
        description:
          "当前未保存的正文会被替换。历史版本只放入编辑框，仍需预览并保存。",
        confirmLabel: "替换编辑内容",
      }))
    ) {
      if (alive.current) setRestoring(false);
      return;
    }
    if (!alive.current || selection.current !== request) return;
    onRestore(candidate.source!, candidate.commit!);
  }

  return (
    <dialog
      ref={dialog}
      className="confirmation-dialog history-dialog"
      aria-labelledby={title}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <div className="history-heading">
        <h2 id={title}>文件历史</h2>
        <button type="button" className="button-secondary" onClick={onClose}>
          关闭
        </button>
      </div>
      <p className="export-source">{file.path}</p>
      <p>
        沿主分支查看此路径的变化，不追踪改名。恢复后仍需保存，保存会产生新版本。
      </p>
      {error && <p role="alert">{error}</p>}
      <div className="history-layout">
        <section aria-label="历史版本" className="history-versions">
          {page?.entries.map((entry) => (
            <button
              type="button"
              className="button-secondary"
              key={entry.commit}
              aria-pressed={selected === entry.commit}
              disabled={restoring}
              onClick={() => void select(entry)}
            >
              <strong>{entry.subject || "无提交说明"}</strong>
              <small>
                {entry.author} ·{" "}
                {new Date(entry.committedAt).toLocaleString("zh-CN")}
              </small>
              <small>
                {entry.commit.slice(0, 8)}
                {entry.present ? "" : " · 已删除"}
              </small>
            </button>
          ))}
          {busy && <p role="status">正在读取历史…</p>}
          {page && !busy && page.entries.length === 0 && (
            <p>这一段没有此路径的变更。</p>
          )}
          {page?.nextOffset !== null && page !== null && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void load(page)}
            >
              继续查看更早记录
            </button>
          )}
          {older && (
            <button type="button" disabled={busy} onClick={() => void load()}>
              返回最近记录
            </button>
          )}
        </section>
        <section aria-label="版本对比" className="history-comparison">
          {selected && <p>所选版本：{selected.slice(0, 8)}</p>}
          {reading && <p role="status">正在读取所选版本…</p>}
          {!selected && <p>选择一个版本，与当前编辑内容比较。</p>}
          {historical?.expectedAbsence && (
            <p>此版本中，文件已经删除。请选择更早的正文版本。</p>
          )}
          {historical &&
            historical.source === null &&
            !historical.expectedAbsence && (
              <p>此版本不是可恢复的 UTF-8 文本，或已超出文本大小限制。</p>
            )}
          {difference?.kind === "unchanged" && (
            <p>所选版本与当前编辑内容相同。</p>
          )}
          {difference?.kind === "lines" && (
            <>
              <p>
                − 历史版本　＋ 当前编辑内容{dirty ? "（含未保存修改）" : ""}
              </p>
              <SourceDiff lines={difference.lines} format={lineEnding} />
            </>
          )}
          {difference?.kind === "side-by-side" && (
            <>
              <p>内容较长，改为并排显示原文。</p>
              <div className="history-sources">
                <section>
                  <h3>历史版本</h3>
                  <pre>{historical?.source}</pre>
                </section>
                <section>
                  <h3>当前编辑内容</h3>
                  <pre>{currentSource}</pre>
                </section>
              </div>
            </>
          )}
          {historical?.source != null && (
            <button
              type="button"
              className="button-primary"
              disabled={
                !writable || restoring || difference?.kind === "unchanged"
              }
              onClick={() => void restore()}
            >
              恢复到编辑框
            </button>
          )}
          {!writable && historical?.source != null && (
            <p>当前权限只允许查看，不能恢复或保存。</p>
          )}
        </section>
      </div>
    </dialog>
  );
}
