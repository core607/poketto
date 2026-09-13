"use client";

import { useEffect, useRef, useState } from "react";
import type { GalleryStatus } from "../lib/types";
import { safeImage } from "../lib/format";
export function Gallery({
  items = [],
  preview = false,
  status = "COMPLETE",
}: {
  items?: { src: string; original: string; alt: string }[];
  preview?: boolean;
  status?: GalleryStatus;
}) {
  const safe = items.filter(
    (item) => safeImage(item.src, preview) && safeImage(item.original, preview),
  );
  const [selected, setSelected] = useState<number | null>(null);
  const [failedSource, setFailedSource] = useState<string | null>(null);
  const [failedPreviews, setFailedPreviews] = useState<string[]>([]);
  const dialog = useRef<HTMLDialogElement>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const active = selected === null ? undefined : safe[selected];
  const open = !!active;
  useEffect(() => {
    if (open) dialog.current?.showModal();
    else {
      dialog.current?.close();
      opener.current?.focus();
    }
  }, [open]);
  const close = () => setSelected(null);
  const step = (direction: number) =>
    setSelected((current) =>
      current === null
        ? null
        : Math.max(0, Math.min(safe.length - 1, current + direction)),
    );
  if (!safe.length && status === "COMPLETE") return null;
  return (
    <section className="gallery" aria-label="同目录图片">
      {status !== "COMPLETE" && (
        <p className="gallery-notice" role="status">
          {status === "PARTIAL"
            ? "部分同目录图片未展示。"
            : "同目录图片暂时无法加载。"}
        </p>
      )}
      {safe.map((item, index) => (
        <figure key={index}>
          <button
            type="button"
            className="gallery-open"
            aria-label={`放大图片：${item.alt || `第 ${index + 1} 张`}`}
            onClick={(event) => {
              opener.current = event.currentTarget;
              setSelected(index);
            }}
          >
            {failedPreviews.includes(item.src) ? (
              <span className="image-unavailable">
                预览暂时不可用 · 点击查看原图
              </span>
            ) : (
              <img
                src={item.src}
                alt={item.alt}
                loading="lazy"
                decoding="async"
                onError={() =>
                  setFailedPreviews((failed) => [...failed, item.src])
                }
              />
            )}
          </button>
          {item.alt && <figcaption>{item.alt}</figcaption>}
        </figure>
      ))}
      <dialog
        ref={dialog}
        className="image-lightbox"
        aria-label="图片预览"
        onCancel={(event) => {
          event.preventDefault();
          close();
        }}
        onClose={close}
        onClick={(event) => {
          if (event.target === event.currentTarget) close();
        }}
        onKeyDown={(event) => {
          if (event.key === "Tab") {
            const controls =
              event.currentTarget.querySelectorAll<HTMLButtonElement>("button");
            const first = controls[0];
            const last = controls[controls.length - 1];
            if (
              (!event.shiftKey && event.target === last) ||
              (event.shiftKey && event.target === first)
            ) {
              event.preventDefault();
              (event.shiftKey ? last : first)?.focus();
            }
          }
          if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault();
            step(event.key === "ArrowLeft" ? -1 : 1);
          }
        }}
      >
        <div className="lightbox-content">
          <header>
            <span>
              {selected === null ? "" : `${selected + 1} / ${safe.length}`}
            </span>
            <button type="button" autoFocus onClick={close}>
              关闭 ×
            </button>
          </header>
          {active &&
            (failedSource === active.original ? (
              <p role="alert">图片暂时无法读取，请重新打开页面。</p>
            ) : (
              <img
                src={active.original}
                alt={active.alt}
                onError={() => setFailedSource(active.original)}
              />
            ))}
          <p>{active?.alt}</p>
          <nav aria-label="图片切换">
            <button
              type="button"
              aria-disabled={selected === null || selected <= 0}
              onClick={() => step(-1)}
            >
              ← 上一张
            </button>
            <button
              type="button"
              aria-disabled={selected === null || selected >= safe.length - 1}
              onClick={() => step(1)}
            >
              下一张 →
            </button>
          </nav>
        </div>
      </dialog>
    </section>
  );
}
