"use client";
import { useEffect } from "react";

/**
 * Writes a newly started discovery batch into the address bar. The page itself answers at its
 * own address, so crawlers see content instead of a redirect, while reload and back keep the
 * batch's order.
 */
export function PinBatch({ href }: { href: string }) {
  useEffect(() => {
    if (window.location.pathname + window.location.search !== href)
      window.history.replaceState(window.history.state, "", href);
  }, [href]);
  return null;
}
