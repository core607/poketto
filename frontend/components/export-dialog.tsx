"use client";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { api, ApiError } from "../lib/browser-api";

type Receipt = {
  handle: string;
  publicOnly: boolean;
  bytes: number;
  sha256: string;
  expiresAt: string;
};

export function ExportDialog({
  source,
  returnFocus,
  fallbackFocus,
  onClose,
}: {
  source: string;
  returnFocus: HTMLElement | null;
  fallbackFocus: HTMLElement | null;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const alive = useRef(true);
  const pending = useRef(false);
  const title = useId();
  const description = useId();
  const [publicOnly, setPublicOnly] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [now, setNow] = useState(Date.now());
  useLayoutEffect(() => {
    const element = dialog.current!;
    alive.current = true;
    element.showModal();
    return () => {
      alive.current = false;
      element.close();
      queueMicrotask(() => {
        if (element.isConnected && element.open) return;
        const target = returnFocus?.isConnected ? returnFocus : fallbackFocus;
        if (target?.isConnected) target.focus();
      });
    };
  }, [returnFocus, fallbackFocus]);
  useEffect(() => {
    if (!receipt) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [receipt]);
  const expired = receipt !== null && now >= Date.parse(receipt.expiresAt);
  function close() {
    alive.current = false;
    onClose();
  }
  async function generate() {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError("");
    setReceipt(null);
    try {
      const result = await api<Receipt>("/api/admin/exports", {
        method: "POST",
        body: { paths: [source], publicOnly },
        timeoutMs: 125000,
      });
      if (!alive.current) {
        // This handle was never offered for download. Delivered handles expire normally,
        // so closing the dialog cannot interrupt a download already owned by the browser.
        void api(
          `/api/admin/exports/${encodeURIComponent(result.handle)}/release`,
          { method: "POST" },
        ).catch(() => {});
        return;
      }
      setReceipt(result);
      setNow(Date.now());
    } catch (failure) {
      if (!alive.current) return;
      const status = failure instanceof ApiError ? failure.status : 0;
      setError(
        status === 429
          ? "导出空间不足或已有任务正在处理，请稍后再试。"
          : status === 503
            ? "无法导出所选内容。请确认公开副本中的文件及附件均已公开，且原件可用。"
            : status === 401 || status === 403
              ? "当前会话无权导出这些内容，请确认登录状态和权限。"
              : "未能取得导出文件，请稍后重试。已生成但未领取的临时文件会自动过期。",
      );
    } finally {
      pending.current = false;
      if (alive.current) setBusy(false);
    }
  }
  return (
    <dialog
      ref={dialog}
      className="confirmation-dialog export-dialog"
      aria-labelledby={title}
      aria-describedby={description}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
    >
      <h2 id={title}>导出 ZIP</h2>
      <p className="export-source">{source || "整个工作空间"}</p>
      <p id={description}>
        导出最新已保存的内容及附件，未保存的修改不会包含在内。导出不会改变公开状态。
      </p>
      <fieldset disabled={busy || (receipt !== null && !expired)}>
        <legend>副本范围</legend>
        <label>
          <input
            type="radio"
            name={title}
            checked={!publicOnly}
            onChange={() => setPublicOnly(false)}
          />
          私人副本<span>保留原文与元数据，可能包含私密内容。</span>
        </label>
        <label>
          <input
            type="radio"
            name={title}
            checked={publicOnly}
            onChange={() => setPublicOnly(true)}
          />
          公开副本<span>用于分享；所选文件及附件必须已经公开。</span>
        </label>
      </fieldset>
      {error && <p role="alert">{error}</p>}
      {busy && <p role="status">正在生成 ZIP… 可以关闭此窗口。</p>}
      {receipt && !expired && (
        <div className="export-ready" role="status">
          <p>
            ZIP 已准备好 ·{" "}
            {receipt.bytes < 1024
              ? `${receipt.bytes} B`
              : receipt.bytes < 1024 * 1024
                ? `${(receipt.bytes / 1024).toLocaleString("zh-CN", { maximumFractionDigits: 2 })} KiB`
                : `${(receipt.bytes / 1024 / 1024).toLocaleString("zh-CN", { maximumFractionDigits: 2 })} MiB`}
          </p>
          <p>
            下载有效期至{" "}
            {new Date(receipt.expiresAt).toLocaleTimeString("zh-CN")}
            。链接仅当前账号可用。
          </p>
          <a
            className="button"
            href={`/api/admin/exports/${encodeURIComponent(receipt.handle)}`}
            download
          >
            下载 ZIP
          </a>
        </div>
      )}
      {expired && <p role="status">下载链接已过期，请重新生成。</p>}
      <div className="confirmation-actions">
        <button type="button" className="button-secondary" onClick={close}>
          {receipt ? "完成" : "取消"}
        </button>
        {(!receipt || expired) && (
          <button
            type="button"
            className="button-primary"
            disabled={busy}
            onClick={() => void generate()}
          >
            生成 ZIP
          </button>
        )}
      </div>
    </dialog>
  );
}
