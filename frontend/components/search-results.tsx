"use client";

import { useEffect, useRef, type ReactNode, type MouseEvent } from "react";
import { searchEntry } from "../lib/search-return";

type Position = { id: string; path: string; anchor: string; scrollY: number };
const STORAGE = "poketto:search-returns";
const HISTORY = "pokettoSearchPosition";

function position(value: unknown, path: string): Position | undefined {
  if (!value || typeof value !== "object") return undefined;
  const item = value as Partial<Position>;
  return searchEntry(item.id) &&
    item.path === path &&
    typeof item.anchor === "string" &&
    item.anchor.startsWith("search-result-") &&
    item.anchor.length <= 4096 &&
    typeof item.scrollY === "number" &&
    Number.isFinite(item.scrollY) &&
    item.scrollY >= 0 &&
    item.scrollY <= 10_000_000
    ? (item as Position)
    : undefined;
}

function stored(): unknown[] {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(STORAGE) ?? "[]");
    return Array.isArray(value) ? value.slice(-32) : [];
  } catch {
    return [];
  }
}

/** History owns each result position; a bounded tab-local copy supports explicit return links. */
export function SearchResults({
  path,
  children,
}: {
  path: string;
  children: ReactNode;
}) {
  const list = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let frame = 0;
    const restore = () => {
      const existing = position(window.history.state?.[HISTORY], path);
      const resume = searchEntry(
        new URLSearchParams(window.location.search).get("resume"),
      );
      const saved =
        existing ??
        stored()
          .map((item) => position(item, path))
          .find((item) => item?.id === resume && resume);
      if (!saved) return;
      frame = requestAnimationFrame(() => {
        const target = document.getElementById(saved.anchor);
        if (!target || !list.current?.contains(target)) return;
        target
          .querySelector<HTMLAnchorElement>("h2 a")
          ?.focus({ preventScroll: true });
        window.scrollTo(0, saved.scrollY);
      });
    };
    restore();
    window.addEventListener("pageshow", restore);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("pageshow", restore);
    };
  }, [path]);

  const remember = (event: MouseEvent<HTMLDivElement>) => {
    const link =
      event.target instanceof Element
        ? event.target.closest<HTMLAnchorElement>("a[data-search-result]")
        : null;
    const card = link?.closest<HTMLElement>(".article-card");
    if (!link || !card || !list.current?.contains(link)) return;
    const href = new URL(link.href, window.location.href);
    if (href.origin !== window.location.origin) return;
    try {
      const saved: Position = {
        id:
          position(window.history.state?.[HISTORY], path)?.id ??
          crypto.randomUUID(),
        path,
        anchor: card.id,
        scrollY: Math.max(0, window.scrollY),
      };
      window.history.replaceState(
        { ...window.history.state, [HISTORY]: saved },
        "",
      );
      // Storage failure must leave the normal query/page return link usable.
      try {
        const history = stored().filter(
          (item) =>
            !item ||
            typeof item !== "object" ||
            (item as Partial<Position>).id !== saved.id,
        );
        sessionStorage.setItem(
          STORAGE,
          JSON.stringify([...history.slice(-31), saved]),
        );
      } catch {}
      href.searchParams.set("searchEntry", saved.id);
      link.href = href.href;
    } catch {}
  };
  return (
    <div ref={list} onClickCapture={remember} onAuxClickCapture={remember}>
      {children}
    </div>
  );
}
