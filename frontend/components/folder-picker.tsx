"use client";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import {
  contentRoot,
  inContentRoot,
  readDirectory,
} from "../lib/repository-directory";
import type { RepositoryDirectory } from "../lib/types";
import { message } from "./admin";

export function FolderPicker({
  source,
  commit,
  returnFocus,
  fallbackFocus,
  canPublish = false,
  onClose,
  onMove,
}: {
  source: string;
  commit: string;
  returnFocus: HTMLElement | null;
  fallbackFocus: HTMLElement | null;
  canPublish?: boolean;
  onClose: () => void;
  onMove: (destination: string) => Promise<boolean>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const title = useId();
  const [folder, setFolder] = useState(
    source.includes("/") ? source.slice(0, source.lastIndexOf("/")) : "",
  );
  const [name, setName] = useState(source.split("/").at(-1)!);
  const [newFolder, setNewFolder] = useState("");
  const [page, setPage] = useState<RepositoryDirectory | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [moving, setMoving] = useState(false);
  useLayoutEffect(() => {
    const element = dialog.current!;
    element.showModal();
    return () => {
      element.close();
      // The same commit can re-enable or replace the trigger after layout cleanup.
      queueMicrotask(() => {
        if (element.isConnected && element.open) return;
        const target = returnFocus?.isConnected ? returnFocus : fallbackFocus;
        if (target?.isConnected) target.focus();
      });
    };
  }, [returnFocus, fallbackFocus]);
  useEffect(() => {
    let active = true;
    setPage(null);
    setLoading(true);
    setError("");
    setNewFolder("");
    void readDirectory(commit, folder)
      .then((result) => {
        if (active) setPage(result);
      })
      .catch((error) => {
        if (active) setError(message(error));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [commit, folder]);
  const parent = [folder, newFolder].filter(Boolean).join("/");
  const destination = parent ? `${parent}/${name}` : name;
  const invalid =
    !name ||
    name.includes("/") ||
    name.includes("\\") ||
    name === "." ||
    name === ".." ||
    destination === source ||
    parent === source ||
    parent.startsWith(source + "/") ||
    newFolder.includes("/") ||
    newFolder.includes("\\") ||
    newFolder === "." ||
    newFolder === "..";
  async function more() {
    if (page?.nextOffset == null || loading) return;
    setLoading(true);
    try {
      const next = await readDirectory(commit, folder, page.nextOffset);
      setPage({ ...next, entries: [...page.entries, ...next.entries] });
    } catch (error) {
      setError(message(error));
    } finally {
      setLoading(false);
    }
  }
  return (
    <dialog
      ref={dialog}
      className="confirmation-dialog folder-picker"
      aria-labelledby={title}
      onCancel={(event) => {
        event.preventDefault();
        if (!moving) onClose();
      }}
    >
      <h2 id={title}>移动到文件夹</h2>
      <p className="muted">{source}</p>
      <div className="folder-navigation" role="group" aria-label="存放范围">
        <button
          type="button"
          disabled={moving || loading}
          aria-pressed={contentRoot(folder) === "private"}
          className={
            contentRoot(folder) === "private" ? undefined : "button-secondary"
          }
          onClick={() => setFolder(inContentRoot(folder, "private"))}
        >
          私有目录
        </button>
        <button
          type="button"
          disabled={moving || loading || !canPublish}
          aria-pressed={contentRoot(folder) === "public"}
          className={
            contentRoot(folder) === "public" ? undefined : "button-secondary"
          }
          title={canPublish ? "保留分类路径，移到 public" : "需要发布权限"}
          onClick={() => setFolder(inContentRoot(folder, "public"))}
        >
          公开目录
        </button>
      </div>
      <p className="muted">
        {contentRoot(folder) === "public"
          ? "移入公开目录后，启用发布且未被排除的内容会在网站展示。单独移动文件不会带走它引用的媒体。"
          : "私有目录中的内容不会在网站展示。移动文件夹会保留内部分类，并带上其中的媒体。"}
      </p>
      <nav className="folder-navigation" aria-label="目标文件夹">
        <button
          type="button"
          disabled={moving || loading || !folder}
          onClick={() => setFolder("")}
        >
          根目录
        </button>
        <button
          type="button"
          disabled={moving || loading || !folder}
          onClick={() =>
            setFolder(
              folder.includes("/")
                ? folder.slice(0, folder.lastIndexOf("/"))
                : "",
            )
          }
        >
          上一级
        </button>
        <span>{folder || "/"}</span>
      </nav>
      <div className="folder-options">
        {page?.entries
          .filter(
            (entry) =>
              entry.kind === "DIRECTORY" &&
              !entry.path
                .split("/")
                .some((part) => part.toLowerCase() === ".poketto"),
          )
          .map((entry) => (
            <button
              type="button"
              key={entry.path}
              disabled={
                moving ||
                loading ||
                entry.path === source ||
                entry.path.startsWith(source + "/")
              }
              onClick={() => setFolder(entry.path)}
            >
              ▸ {entry.path.split("/").at(-1)}
            </button>
          ))}
        {page?.expectedAbsence ? (
          <p className="muted">此目录将在移动时创建。</p>
        ) : (
          page &&
          !page.entries.some((entry) => entry.kind === "DIRECTORY") && (
            <p className="muted">没有子文件夹，可以选择当前文件夹。</p>
          )
        )}
        {page?.nextOffset != null && (
          <button
            type="button"
            disabled={moving || loading}
            onClick={() => void more()}
          >
            加载更多文件夹
          </button>
        )}
      </div>
      {loading && <p role="status">正在读取目录…</p>}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
      <label>
        名称
        <input
          value={name}
          maxLength={255}
          disabled={moving}
          onChange={(event) => {
            setName(event.target.value);
            setError("");
          }}
        />
      </label>
      <label>
        新建子文件夹（可选）
        <input
          value={newFolder}
          maxLength={255}
          disabled={moving}
          onChange={(event) => {
            setNewFolder(event.target.value);
            setError("");
          }}
        />
      </label>
      <p className="move-destination">目标：{destination}</p>
      <div className="confirmation-actions">
        <button
          type="button"
          className="button-secondary"
          autoFocus
          disabled={moving}
          onClick={onClose}
        >
          取消
        </button>
        <button
          type="button"
          disabled={moving || loading || invalid || !page || !!error}
          onClick={async () => {
            setMoving(true);
            try {
              if (await onMove(destination)) onClose();
            } catch (error) {
              setError(message(error));
            } finally {
              setMoving(false);
            }
          }}
        >
          {moving ? "正在移动…" : "移动到这里"}
        </button>
      </div>
    </dialog>
  );
}
