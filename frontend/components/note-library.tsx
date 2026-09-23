"use client";
import { useMemo, useState } from "react";
import type { RepositoryTree } from "../lib/types";
import { contentRoot } from "../lib/repository-directory";
import { Icon } from "./ui/icons";

type Filter = "drafts" | "published";

/** Guides and settings are repository plumbing, not notes a writer looks for. */
function isNote(path: string) {
  const name = path.split("/").at(-1) ?? "";
  return (
    contentRoot(path) !== null &&
    name.toLowerCase() !== "agents.md" &&
    !path.split("/").some((part) => part.startsWith("."))
  );
}

function category(path: string) {
  return path.split("/").slice(1, -1).join(" / ");
}

function when(value?: string | null) {
  if (!value) return "";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
  }).format(new Date(value));
}

/**
 * The content home for people who think in notes rather than files: drafts and published notes
 * with their categories, newest first. Everything here opens the same editor as the file tree.
 */
export function NoteLibrary({
  tree,
  busy,
  canWrite,
  onOpen,
  onCreate,
}: {
  tree: RepositoryTree | null;
  busy: boolean;
  canWrite: boolean;
  onOpen: (path: string) => void;
  onCreate: (kind: "note" | "folder", trigger: HTMLButtonElement) => void;
}) {
  const [filter, setFilter] = useState<Filter>("drafts");
  const [query, setQuery] = useState("");
  const notes = useMemo(
    () =>
      (tree?.entries ?? [])
        .filter((entry) => isNote(entry.path))
        .sort((left, right) =>
          (right.updatedAt ?? "").localeCompare(left.updatedAt ?? ""),
        ),
    [tree],
  );
  const drafts = notes.filter((entry) => contentRoot(entry.path) === "private");
  const published = notes.filter(
    (entry) => contentRoot(entry.path) === "public",
  );
  const needle = query.trim().toLowerCase();
  const visible = (filter === "drafts" ? drafts : published).filter(
    (entry) =>
      !needle ||
      entry.title.toLowerCase().includes(needle) ||
      entry.path.toLowerCase().includes(needle),
  );
  return (
    <section className="note-library" aria-labelledby="note-library-title">
      <header className="library-head">
        <div>
          <h2 id="note-library-title">我的笔记</h2>
          <p className="muted">
            草稿只有空间成员能看到；发布后会出现在这个空间的网站上。
          </p>
        </div>
        {canWrite && (
          <div className="library-actions">
            <button
              type="button"
              className="button-secondary"
              disabled={busy}
              onClick={(event) => onCreate("folder", event.currentTarget)}
              title="分类会成为网站上的一个栏目；放进图片，它就是一个相册"
            >
              <Icon name="folder" />
              新建分类
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={(event) => onCreate("note", event.currentTarget)}
            >
              <Icon name="pen" />
              写新笔记
            </button>
          </div>
        )}
      </header>
      <div className="library-bar">
        <div className="segmented" role="tablist" aria-label="笔记状态">
          <button
            type="button"
            role="tab"
            aria-selected={filter === "drafts"}
            onClick={() => setFilter("drafts")}
          >
            <Icon name="lock" />
            草稿 <span className="count">{drafts.length}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={filter === "published"}
            onClick={() => setFilter("published")}
          >
            <Icon name="globe" />
            已发布 <span className="count">{published.length}</span>
          </button>
        </div>
        <label className="library-filter">
          <span className="sr-only">按标题筛选</span>
          <Icon name="search" />
          <input
            type="search"
            placeholder="按标题筛选"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
      </div>
      {!tree ? (
        <p className="muted" role="status">
          正在读取笔记…
        </p>
      ) : visible.length ? (
        <ul className="note-list">
          {visible.map((entry) => (
            <li key={entry.path}>
              <button
                type="button"
                disabled={busy}
                title={entry.path}
                onClick={() => onOpen(entry.path)}
              >
                <span className="note-icon" aria-hidden>
                  <Icon name={entry.folderPage ? "folder" : "file"} />
                </span>
                <span className="note-text">
                  <strong>{entry.title || entry.path.split("/").at(-1)}</strong>
                  <small>
                    {entry.folderPage
                      ? `分类「${category(entry.path) || "首页"}」的介绍页`
                      : category(entry.path) || "未分类"}
                    {entry.updatedAt && ` · ${when(entry.updatedAt)}更新`}
                  </small>
                </span>
                <Icon name="chevronRight" />
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <div className="empty">
          <span className="empty-mark">
            <Icon name={filter === "drafts" ? "pen" : "globe"} />
          </span>
          <h3>
            {needle
              ? "没有标题匹配的笔记。"
              : filter === "drafts"
                ? "还没有草稿。"
                : "还没有发布的笔记。"}
          </h3>
          <p>
            {filter === "drafts"
              ? "写一篇新笔记吧，它会先作为草稿保存。"
              : "打开一篇草稿，在顶部点「发布」，它就会出现在网站上。"}
          </p>
        </div>
      )}
      <details className="library-help">
        <summary>这些都保存在哪里？</summary>
        <p>
          每篇笔记都是一个文字文件，保存在这个空间的仓库里。仓库就像一个会记住每次修改的云端文件夹：你可以随时查看历史版本、恢复旧内容，也可以把整个空间带走。
        </p>
        <p>
          「分类」是一个文件夹：它的介绍页会成为网站上的一个栏目；往里放图片，它就是一个相册。左侧的「全部文件」按文件夹展示所有内容，适合需要精细整理的时候。
        </p>
      </details>
    </section>
  );
}
