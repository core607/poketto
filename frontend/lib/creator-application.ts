"use client";
import { useCallback, useEffect, useState } from "react";
import { api, message } from "./browser-api";
import type { SiteGroup } from "./site-groups";

export type CreatorApplicationRecord = {
  id: string;
  statement: string;
  status: "OPEN" | "APPROVED" | "DECLINED";
  createdAt: string;
  decidedAt: string | null;
  decisionNote: string | null;
  read: boolean;
};
export type CreatorStanding = {
  group: SiteGroup;
  latest: CreatorApplicationRecord | null;
  availableAt: string | null;
  unread: CreatorApplicationRecord | null;
};
const changed = "poketto:creator-application";

export function applicationChanged() {
  window.dispatchEvent(new Event(changed));
}

/** Rechecks pending decisions while visible and whenever the account returns to the page. */
export function useCreatorStanding() {
  const [standing, setStanding] = useState<CreatorStanding | null>(null);
  const [error, setError] = useState("");
  const [version, setVersion] = useState(0);
  const refresh = useCallback(() => setVersion((value) => value + 1), []);
  useEffect(() => {
    let active = true;
    api<CreatorStanding>("/api/auth/creator-application")
      .then((value) => {
        if (active) {
          setStanding(value);
          setError("");
        }
      })
      .catch((failure) => {
        if (active) setError(message(failure));
      });
    return () => {
      active = false;
    };
  }, [version]);
  useEffect(() => {
    const visible = () => {
      if (document.visibilityState !== "hidden") refresh();
    };
    window.addEventListener("focus", visible);
    window.addEventListener(changed, refresh);
    document.addEventListener("visibilitychange", visible);
    const timer =
      standing?.latest?.status === "OPEN"
        ? window.setInterval(visible, 30000)
        : undefined;
    return () => {
      window.removeEventListener("focus", visible);
      window.removeEventListener(changed, refresh);
      document.removeEventListener("visibilitychange", visible);
      if (timer !== undefined) window.clearInterval(timer);
    };
  }, [standing?.latest?.status, refresh]);
  return { standing, setStanding, error, refresh };
}
