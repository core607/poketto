"use client";

import { useEffect, useRef, useState } from "react";
import { useWorkspaceApi } from "./workspace-context";
import { message } from "../lib/browser-api";
import { SearchHighlight } from "./search-highlight";

type Page = {
  commit: string | null;
  paths: string[];
  total: number;
  offset: number;
  limit: number;
};

export function FilenameSearch({
  busy,
  commit,
  onOpen,
}: {
  busy: boolean;
  commit: string | null;
  onOpen: (path: string) => void;
}) {
  const api = useWorkspaceApi();
  const [query, setQuery] = useState("");
  const [page, setPage] = useState<(Page & { query: string }) | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setPage(null);
    setError("");
    setPending(false);
    return () => {
      generation.current++;
    };
  }, [commit]);

  async function search(
    value: string,
    offset = 0,
    pinnedCommit?: string | null,
  ) {
    const version = ++generation.current;
    setPending(true);
    setError("");
    const parameters = new URLSearchParams({
      query: value,
      offset: String(offset),
      limit: "50",
    });
    if (pinnedCommit) parameters.set("commit", pinnedCommit);
    try {
      const result = await api<Page>(
        "/api/admin/repository/filenames?" + parameters,
      );
      if (version === generation.current) setPage({ ...result, query: value });
    } catch (failure) {
      if (version === generation.current) setError(message(failure));
    } finally {
      if (version === generation.current) setPending(false);
    }
  }

  return (
    <section className="filename-search" aria-label="搜索文件名">
      <form
        className="open-path"
        onSubmit={(event) => {
          event.preventDefault();
          void search(query);
        }}
      >
        <label>
          搜索文件名
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            required
            maxLength={200}
            placeholder="搜索所有可访问的文件路径…"
          />
        </label>
        <button
          type="submit"
          className="button-secondary"
          disabled={busy || pending}
        >
          搜索文件名
        </button>
      </form>
      {pending && (
        <p className="muted" role="status">
          正在查找文件…
        </p>
      )}
      {error && (
        <p className="notice danger" role="alert">
          {error} 请重新搜索以读取当前目录。
        </p>
      )}
      {page && (
        <div className="private-results">
          <div className="sidebar-title">
            <span role="status">文件名结果 · {page.total} 个</span>
            <button
              type="button"
              className="text-button"
              onClick={() => {
                generation.current++;
                setPage(null);
                setError("");
                setPending(false);
              }}
            >
              收起
            </button>
          </div>
          {page.paths.map((path) => (
            <button
              key={path}
              type="button"
              disabled={busy || pending}
              onClick={() => onOpen(path)}
            >
              <SearchHighlight text={path} query={page.query} />
            </button>
          ))}
          {!page.paths.length && <p className="muted">没有匹配的文件名。</p>}
          <nav className="filename-pagination" aria-label="文件名结果翻页">
            {page.offset > 0 && (
              <button
                type="button"
                disabled={busy || pending}
                onClick={() =>
                  void search(
                    page.query,
                    Math.max(0, page.offset - page.limit),
                    page.commit,
                  )
                }
              >
                上一页
              </button>
            )}
            {page.offset + page.limit < page.total && (
              <button
                type="button"
                disabled={busy || pending}
                onClick={() =>
                  void search(page.query, page.offset + page.limit, page.commit)
                }
              >
                下一页
              </button>
            )}
          </nav>
        </div>
      )}
    </section>
  );
}
