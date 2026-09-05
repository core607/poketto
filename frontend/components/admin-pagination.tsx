"use client";
import { useEffect, useState } from "react";
import { api } from "../lib/browser-api";
import { message } from "./admin";
export type AdminPage<T> = {
  items: T[];
  total: number;
  offset: number;
  limit: number;
};
export function useAdminPage<T>(path: string) {
  const [offset, setOffset] = useState(0);
  const [version, setVersion] = useState(0);
  const [page, setPage] = useState<AdminPage<T>>({
    items: [],
    total: 0,
    offset: 0,
    limit: 30,
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    api<AdminPage<T>>(`${path}?offset=${offset}&limit=30`)
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
  }, [path, offset, version]);
  return {
    ...page,
    loading,
    error,
    setOffset,
    reload: () => setVersion((value) => value + 1),
  };
}
export function AdminPagination({
  label,
  page,
  disabled = false,
}: {
  label: string;
  page: {
    offset: number;
    limit: number;
    total: number;
    loading: boolean;
    error: string;
    reload: () => void;
    setOffset: (offset: number) => void;
  };
  disabled?: boolean;
}) {
  return (
    <nav className="pagination" aria-label={label + "分页"}>
      {page.error && (
        <button
          type="button"
          className="text-button"
          disabled={disabled || page.loading}
          onClick={page.reload}
        >
          重试读取
        </button>
      )}
      <button
        type="button"
        className="text-button"
        disabled={disabled || page.loading || page.offset === 0}
        onClick={() => page.setOffset(Math.max(0, page.offset - page.limit))}
      >
        上一页
      </button>
      <span className="muted" aria-live="polite">
        {page.loading
          ? "正在读取…"
          : `第 ${Math.floor(page.offset / page.limit) + 1} 页 · 共 ${page.total} 条`}
      </span>
      <button
        type="button"
        className="text-button"
        disabled={
          disabled || page.loading || page.offset + page.limit >= page.total
        }
        onClick={() => page.setOffset(page.offset + page.limit)}
      >
        下一页
      </button>
    </nav>
  );
}
