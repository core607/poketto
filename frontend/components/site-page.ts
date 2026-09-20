"use client";
import { useEffect, useState } from "react";
import { api } from "../lib/browser-api";
import { message } from "./admin";
import { AdminPage } from "./admin-pagination";

export function useSitePage<T>(path: string) {
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
    api<AdminPage<T>>(
      `${path}${path.includes("?") ? "&" : "?"}offset=${offset}&limit=30`,
    )
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
