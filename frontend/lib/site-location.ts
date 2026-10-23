"use client";
import { useSyncExternalStore } from "react";

const changed = "poketto:site-location";
function subscribe(listener: () => void) {
  window.addEventListener("popstate", listener);
  window.addEventListener(changed, listener);
  return () => {
    window.removeEventListener("popstate", listener);
    window.removeEventListener(changed, listener);
  };
}

export function useSiteLocation() {
  const search = useSyncExternalStore(
    subscribe,
    () => window.location.search,
    () => "",
  );
  return new URLSearchParams(search);
}

export function siteHref(values: Record<string, string | number | null>) {
  const url = new URL(
    typeof window === "undefined"
      ? "https://site.invalid/site"
      : window.location.href,
  );
  url.pathname = "/site";
  url.hash = "";
  for (const [name, value] of Object.entries(values)) {
    if (value === null || value === "" || value === 0)
      url.searchParams.delete(name);
    else url.searchParams.set(name, String(value));
  }
  return url.pathname + url.search;
}

export function navigateSite(values: Record<string, string | number | null>) {
  const next = siteHref(values);
  if (next === window.location.pathname + window.location.search) return;
  window.history.pushState(window.history.state, "", next);
  window.dispatchEvent(new window.Event(changed));
}

export function pageOffset(query: URLSearchParams, key: string) {
  const value = Number(query.get(key) ?? 0);
  return Number.isSafeInteger(value) && value >= 0 && value <= 100000
    ? value
    : 0;
}

export function siteChoice<T extends string>(
  query: URLSearchParams,
  key: string,
  choices: readonly T[],
  fallback: T,
) {
  const value = query.get(key);
  return choices.includes(value as T) ? (value as T) : fallback;
}
