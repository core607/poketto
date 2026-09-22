"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import type { Identity } from "./admin";
import type { RepositoryFile } from "../lib/types";
import {
  localDrafts,
  draftGeneration,
  removeDraft,
  removeUnchangedDraft,
  retainDraft,
  withDraftStorage,
  type LocalDraft,
} from "../lib/local-drafts";

type Editing = { id: string; latest: LocalDraft | null; restored?: LocalDraft };

export function useEditorRecovery(
  identity: Identity,
  file: RepositoryFile | null,
  path: string,
  source: string,
  dirty: boolean,
  writable: boolean,
) {
  const editing = useRef<Editing | null>(null);
  const mounted = useRef(false);
  const generation = useRef("");
  const [available, setAvailable] = useState<LocalDraft[]>([]);
  const [status, setStatus] = useState("");
  const pending = useRef<(() => void) | null>(null);
  const delay = useRef<number | null>(null);
  const deadline = useRef<number | null>(null);
  const cancelPending = useCallback(() => {
    if (delay.current !== null) window.clearTimeout(delay.current);
    if (deadline.current !== null) window.clearTimeout(deadline.current);
    delay.current = deadline.current = null;
    pending.current = null;
  }, []);
  const flush = useCallback(() => {
    const write = pending.current;
    cancelPending();
    write?.();
  }, [cancelPending]);
  useEffect(() => {
    mounted.current = true;
    try {
      generation.current = draftGeneration(
        window.localStorage,
        identity.accountId,
      );
    } catch {}
    const hide = () => {
      if (document.visibilityState === "hidden") flush();
    };
    window.addEventListener("pagehide", flush);
    document.addEventListener("visibilitychange", hide);
    return () => {
      window.removeEventListener("pagehide", flush);
      document.removeEventListener("visibilitychange", hide);
      cancelPending();
      mounted.current = false;
      editing.current = null;
    };
  }, [cancelPending, flush]);
  const discard = useCallback(() => {
    cancelPending();
    const previous = editing.current;
    editing.current = null;
    if (mounted.current) setStatus("");
    if (!previous) return;
    void withDraftStorage((storage) => {
      if (previous.latest) removeDraft(storage, previous.latest);
      if (previous.restored) removeUnchangedDraft(storage, previous.restored);
    })
      .then(() => {
        if (mounted.current)
          setAvailable((items) =>
            items.filter(
              (item) =>
                item.id !== previous.latest?.id &&
                item.id !== previous.restored?.id,
            ),
          );
      })
      .catch(() => {
        if (mounted.current)
          setStatus("本机草稿未能清理，请在恢复入口中重试。");
      });
  }, [cancelPending]);
  function opened(current: RepositoryFile) {
    cancelPending();
    editing.current = null;
    setAvailable([]);
    setStatus("");
    try {
      generation.current = draftGeneration(
        window.localStorage,
        identity.accountId,
      );
      const saved = localDrafts(
        window.localStorage,
        identity.accountId,
        identity.workspaceId,
      ).filter((draft) => draft.path === current.path);
      setAvailable(saved.filter((draft) => draft.source !== current.source));
      void withDraftStorage((storage) => {
        for (const draft of saved)
          if (draft.source === current.source)
            removeUnchangedDraft(storage, draft);
      }).catch(() => {});
    } catch {
      setStatus("此浏览器无法读取本机草稿；仓库内容仍可正常编辑。");
    }
  }
  function adopt(draft: LocalDraft) {
    editing.current = {
      id: crypto.randomUUID(),
      latest: null,
      restored: draft,
    };
    setAvailable([]);
  }
  async function forget(draft: LocalDraft) {
    try {
      await withDraftStorage((storage) => removeUnchangedDraft(storage, draft));
      if (mounted.current)
        setAvailable((items) => items.filter((item) => item.id !== draft.id));
    } catch {
      if (mounted.current) setStatus("本机草稿未能清理，请稍后重试。");
    }
  }
  useEffect(() => {
    if (!dirty) {
      if (editing.current) discard();
      return;
    }
    if (
      !file ||
      !writable ||
      (editing.current === null &&
        source === (file.source ?? "") &&
        path === file.path)
    )
      return;
    const selected = editing.current ?? {
      id: crypto.randomUUID(),
      latest: null,
    };
    editing.current = selected;
    const draft: LocalDraft = {
      version: 1,
      id: selected.id,
      accountId: identity.accountId,
      workspaceId: identity.workspaceId,
      path,
      source,
      updatedAt: Date.now(),
      baseline: {
        path: file.path,
        commit: file.commit,
        revision: file.revision,
        expectedAbsence: file.expectedAbsence,
      },
    };
    selected.latest = draft;
    const expectedGeneration = generation.current;
    setStatus("正在保存本机恢复草稿…");
    pending.current = () => {
      void withDraftStorage((storage) => {
        if (editing.current === selected && selected.latest === draft)
          retainDraft(storage, draft, expectedGeneration);
      })
        .then(() => {
          if (
            mounted.current &&
            editing.current === selected &&
            selected.latest === draft
          )
            setStatus(
              "恢复草稿已保留在此浏览器；尚未保存到仓库。退出登录会清理本机草稿。",
            );
        })
        .catch((failure) => {
          if (
            mounted.current &&
            editing.current === selected &&
            selected.latest === draft
          )
            setStatus(
              failure instanceof Error && failure.message.startsWith("本机")
                ? failure.message
                : "本机恢复草稿未能保存，请保存到仓库或复制正文后再离开。",
            );
        });
    };
    if (delay.current !== null) window.clearTimeout(delay.current);
    delay.current = window.setTimeout(flush, 250);
    if (deadline.current === null)
      deadline.current = window.setTimeout(flush, 1000);
  }, [
    file,
    path,
    source,
    dirty,
    writable,
    identity.accountId,
    identity.workspaceId,
    discard,
    flush,
  ]);
  return { available, status, opened, adopt, forget, discard };
}

export function DraftRecovery({
  drafts,
  disabled,
  onRestore,
  onForget,
}: {
  drafts: LocalDraft[];
  disabled: boolean;
  onRestore: (draft: LocalDraft) => void;
  onForget: (draft: LocalDraft) => void;
}) {
  if (!drafts.length) return null;
  return (
    <section className="notice" aria-label="恢复本机草稿">
      <p>此文件有未保存的本机草稿。恢复只放回编辑框，仍需预览和保存。</p>
      {drafts.map((draft) => (
        <div className="button-row" key={draft.id}>
          <time dateTime={new Date(draft.updatedAt).toISOString()}>
            {new Date(draft.updatedAt).toLocaleString("zh-CN")}
          </time>
          <button
            type="button"
            disabled={disabled}
            onClick={() => onRestore(draft)}
          >
            恢复草稿
          </button>
          <button
            type="button"
            className="text-button"
            disabled={disabled}
            onClick={() => onForget(draft)}
          >
            删除这份本机草稿
          </button>
        </div>
      ))}
    </section>
  );
}
