"use client";
import { useLayoutEffect, useRef } from "react";

/**
 * Shows the referenced dialog modally while it is mounted. Unmounting closes it and returns focus
 * to returnFocus, or to fallbackFocus once returnFocus has left the document.
 */
export function useModal(
  returnFocus: HTMLElement | null,
  fallbackFocus: HTMLElement | null = null,
) {
  const dialog = useRef<HTMLDialogElement>(null);
  useLayoutEffect(() => {
    const element = dialog.current!;
    element.showModal();
    return () => {
      element.close();
      // The same commit can re-enable or replace the trigger after layout cleanup.
      queueMicrotask(() => {
        if (element.isConnected && element.open) return;
        const target = returnFocus?.isConnected ? returnFocus : fallbackFocus;
        if (target?.isConnected) target.focus();
      });
    };
  }, [returnFocus, fallbackFocus]);
  return dialog;
}
