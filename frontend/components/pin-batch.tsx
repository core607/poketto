"use client";
import { useEffect } from "react";

/**
 * Writes a newly started discovery batch into the address bar. The page itself answers at its
 * own address, so crawlers see content instead of a redirect. Once this has run, reload and back
 * keep the batch's order; before scripts run, or without them, a reload starts a new batch.
 */
export function PinBatch({ href }: { href: string }) {
  useEffect(() => {
    if (window.location.pathname + window.location.search !== href)
      window.history.replaceState(window.history.state, "", href);
  }, [href]);
  return null;
}
