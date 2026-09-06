"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "../lib/browser-api";
import { safeImage } from "../lib/format";
import { message } from "./admin";
type Asset = {
  reference: { assetId: string; revision: string };
  mediaType: string;
  size: number;
};
type RepositoryImage = { path: string; mediaType: string; size: number };
type Preview = { images?: Record<string, string> };
export function AssetPicker({
  path,
  commit,
  onInsert,
}: {
  path: string;
  commit: string | null;
  onInsert: (markdown: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [items, setItems] = useState<{ source: string; label: string }[]>([]);
  const [images, setImages] = useState<Record<string, string>>({});
  const [upload, setUpload] = useState<{
    file: File;
    operation: string;
  } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [offset, setOffset] = useState(0);
  const [total, setTotal] = useState(0);
  const [kind, setKind] = useState("managed");
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  async function list(nextKind = kind, nextOffset = 0) {
    setExpanded(true);
    setBusy(true);
    setError("");
    setNotice("");
    try {
      let next: { source: string; label: string }[];
      if (nextKind === "managed") {
        const page = await api<{ items: Asset[]; total: number }>(
          `/api/admin/assets?offset=${nextOffset}&limit=12`,
        );
        setTotal(page.total);
        next = page.items.map((item) => ({
          source: `managed:${item.reference.assetId}:${item.reference.revision}`,
          label: `${item.mediaType.split("/")[1]?.toUpperCase()} · ${(item.size / 1024).toFixed(0)} KB`,
        }));
      } else {
        const parameters = new URLSearchParams({
          offset: String(nextOffset),
          limit: "12",
        });
        if (commit) parameters.set("commit", commit);
        const page = await api<{ items: RepositoryImage[]; total: number }>(
          "/api/admin/assets/repository?" + parameters,
        );
        setTotal(page.total);
        next = page.items.map((item) => ({
          source: relativePath(path, item.path),
          label: item.path,
        }));
      }
      setItems(next);
      setKind(nextKind);
      setOffset(nextOffset);
      const preview = await api<Preview>("/api/admin/repository/preview", {
        method: "POST",
        body: {
          path,
          body: next.map((item) => `![](<${item.source}>)`).join("\n"),
          commit,
        },
      });
      setImages(preview.images ?? {});
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy(false);
    }
  }
  async function send() {
    if (!upload) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const data = new FormData();
      data.append("file", upload.file);
      const asset = await api<Asset>("/api/admin/assets", {
        method: "POST",
        multipart: data,
        headers: { "Idempotency-Key": upload.operation },
      });
      const reference = `managed:${asset.reference.assetId}:${asset.reference.revision}`;
      if (!mounted.current) return;
      onInsert(`![图片](${reference})`);
      setUpload(null);
      setNotice("图片已上传并插入草稿；保存文章后才会更新内容。");
    } catch (error) {
      setError(message(error));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="asset-picker">
      <div className="button-row">
        <button
          type="button"
          className="button-secondary"
          disabled={busy}
          onClick={() => void list("managed")}
        >
          选择已上传图片
        </button>
        <button
          type="button"
          className="button-secondary"
          disabled={busy}
          onClick={() => void list("repository")}
        >
          选择仓库图片
        </button>
        <label className="upload-label">
          上传图片
          <input
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            disabled={busy}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (!file) return;
              if (file.size > 16 * 1024 * 1024) {
                setError("图片不能超过 16 MiB。");
                return;
              }
              setUpload({ file, operation: crypto.randomUUID() });
              setError("");
            }}
          />
        </label>
        {upload && (
          <button disabled={busy} onClick={send}>
            {busy ? "上传中…" : `上传 ${upload.file.name}`}
          </button>
        )}
      </div>
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
      {expanded && (
        <div className="asset-shelf">
          <div className="panel-heading">
            <span>
              {kind === "repository"
                ? "仓库图片只读，选择后插入相对路径。"
                : "选择图片插入当前草稿。"}
            </span>
            <button className="text-button" onClick={() => setExpanded(false)}>
              收起
            </button>
          </div>
          <div className="asset-grid">
            {items.map((item) => (
              <button
                key={item.source}
                onClick={() => {
                  onInsert(`![图片](<${item.source}>)`);
                  setExpanded(false);
                }}
                disabled={busy}
              >
                {safeImage(images[item.source], true) ? (
                  <img
                    src={images[item.source]}
                    alt={item.label}
                    loading="lazy"
                  />
                ) : (
                  <span className="asset-placeholder" aria-hidden>
                    ▧
                  </span>
                )}
                <span>{item.label}</span>
              </button>
            ))}
          </div>
          {!items.length && !busy && (
            <p className="muted">还没有可选择的图片。</p>
          )}
          <div className="pagination">
            <button
              className="text-button"
              disabled={busy || offset === 0}
              onClick={() => void list(kind, Math.max(0, offset - 12))}
            >
              ← 上一页
            </button>
            <span>共 {total} 张</span>
            <button
              className="text-button"
              disabled={busy || offset + 12 >= total}
              onClick={() => void list(kind, offset + 12)}
            >
              下一页 →
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
export function relativePath(file: string, target: string) {
  const parent = file.split("/").slice(0, -1);
  const destination = target.split("/");
  while (parent.length && destination.length && parent[0] === destination[0]) {
    parent.shift();
    destination.shift();
  }
  return [
    ...parent.map(() => ".."),
    ...destination.map(encodeURIComponent),
  ].join("/");
}
