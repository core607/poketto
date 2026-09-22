"use client";
import { useEffect, useRef, useState } from "react";
import {
  localDrafts,
  removeUnchangedDraft,
  withDraftStorage,
  type LocalDraft,
} from "../lib/local-drafts";
import type { RepositoryFile } from "../lib/types";
import { ApiError } from "../lib/browser-api";
import type { Identity } from "./admin";
import { useWorkspaceApi } from "./workspace-context";
import { useConfirmation } from "./confirmation";

export function DraftLibrary({
  identity,
  disabled,
  onOpen,
}: {
  identity: Identity;
  disabled: boolean;
  onOpen: (path: string) => void;
}) {
  const api = useWorkspaceApi();
  const confirm = useConfirmation();
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<LocalDraft[]>([]);
  const [error, setError] = useState("");
  const request = useRef(0);
  useEffect(
    () => () => {
      request.current++;
    },
    [],
  );
  async function read() {
    const selected = ++request.current;
    setExpanded(true);
    setLoading(true);
    setError("");
    setItems([]);
    try {
      const candidates = localDrafts(
        window.localStorage,
        identity.accountId,
        identity.workspaceId,
      );
      const allowed = new Map<string, boolean>();
      for (const draft of candidates) {
        if (selected !== request.current) return;
        if (allowed.has(draft.path)) continue;
        try {
          const current = await api<RepositoryFile>(
            "/api/admin/repository/file?" +
              new URLSearchParams({ path: draft.path }),
          );
          allowed.set(
            draft.path,
            current.path === draft.path &&
              (current.source !== null || current.expectedAbsence),
          );
        } catch (failure) {
          if (!(
            failure instanceof ApiError &&
            [400, 403, 404].includes(failure.status)
          ))
            throw failure;
          allowed.set(draft.path, false);
        }
      }
      if (selected === request.current)
        setItems(candidates.filter((draft) => allowed.get(draft.path)));
    } catch {
      if (selected === request.current)
        setError("本机草稿暂时无法读取或确认访问权限，请稍后重试。");
    } finally {
      if (selected === request.current) setLoading(false);
    }
  }
  return (
    <section className="draft-library">
      <button
        type="button"
        className="text-button"
        disabled={disabled || loading}
        onClick={() => void read()}
      >
        本机草稿
      </button>
      {expanded && (
        <>
          <p className="muted">
            仅在此浏览器恢复未保存的正文；退出登录时清理。
          </p>
          {loading && <p role="status">正在确认草稿访问权限…</p>}
          {error && <p role="alert">{error}</p>}
          {!loading && !error && !items.length && (
            <p className="muted">没有可恢复的本机草稿。</p>
          )}
          {items.map((draft) => (
            <div className="draft-library-item" key={draft.id}>
              <button
                type="button"
                className="text-button"
                disabled={disabled}
                onClick={() => {
                  setExpanded(false);
                  onOpen(draft.path);
                }}
              >
                {draft.path}
              </button>
              <small>{new Date(draft.updatedAt).toLocaleString("zh-CN")}</small>
              <button
                type="button"
                className="text-button"
                disabled={disabled}
                onClick={async () => {
                  if (
                    !(await confirm({
                      title: "删除这份本机草稿？",
                      description: `「${draft.path}」的这份未保存正文将从浏览器移除，仓库文件不变。`,
                      confirmLabel: "删除本机草稿",
                    }))
                  )
                    return;
                  try {
                    await withDraftStorage((storage) =>
                      removeUnchangedDraft(storage, draft),
                    );
                    await read();
                  } catch {
                    setError("本机草稿未能清理，请稍后重试。");
                  }
                }}
              >
                删除
              </button>
            </div>
          ))}
          <button
            type="button"
            className="text-button"
            onClick={() => {
              request.current++;
              setExpanded(false);
              setLoading(false);
            }}
          >
            收起本机草稿
          </button>
        </>
      )}
    </section>
  );
}
