"use client";
import { useEffect } from "react";

const STORE = "poketto:read-today";
const VISIBLE_MS = 5000;

/**
 * Reports one reader once the article has stayed visible for five seconds, at most once per
 * browser, article and UTC day. The server deduplicates as well, so an unreadable store only
 * means an extra request. See notes/implemented/2026-09-24-public-view-counts.md.
 */
export function ViewBeacon({ space, route }: { space: string; route: string }) {
  useEffect(() => {
    const day = new Date().toISOString().slice(0, 10);
    const entry = space + "\n" + route;
    if (readToday(day).includes(entry)) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const send = () => {
      timer = undefined;
      document.removeEventListener("visibilitychange", update);
      const address =
        `/api/public/community/spaces/${encodeURIComponent(space)}/views?` +
        new URLSearchParams({ route });
      if (!navigator.sendBeacon?.(address))
        void fetch(address, { method: "POST", keepalive: true }).catch(
          () => {},
        );
      remember(day, entry);
    };
    const update = () => {
      if (document.visibilityState === "visible") {
        timer ??= setTimeout(send, VISIBLE_MS);
      } else if (timer) {
        clearTimeout(timer);
        timer = undefined;
      }
    };
    update();
    document.addEventListener("visibilitychange", update);
    return () => {
      if (timer) clearTimeout(timer);
      document.removeEventListener("visibilitychange", update);
    };
  }, [space, route]);
  return null;
}

function readToday(day: string): string[] {
  try {
    const stored = JSON.parse(localStorage.getItem(STORE) ?? "null");
    return stored?.day === day && Array.isArray(stored.entries)
      ? stored.entries
      : [];
  } catch {
    return [];
  }
}

function remember(day: string, entry: string) {
  try {
    const entries = [...readToday(day), entry].slice(-500);
    localStorage.setItem(STORE, JSON.stringify({ day, entries }));
  } catch {
    // Without storage the server's own deduplication still applies.
  }
}
