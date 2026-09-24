"use client";
import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "../lib/browser-api";

type Space = {
  workspaceId: string;
  displayName: string;
  role: "OWNER" | "MEMBER";
  capabilities: string[];
};
type Captured = { path: string; commit: string };

const LAST_SPACE = "poketto:capture-space";

/**
 * The bookmarklet's popup: it arrives with the page's title, address and selection, and saves one
 * private inbox note with the signed-in browser session. Opening it never writes; only the form does.
 */
export function CaptureForm({
  title,
  url,
  text,
}: {
  title: string;
  url: string;
  text: string;
}) {
  const [spaces, setSpaces] = useState<Space[] | null>(null);
  const [space, setSpace] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [saved, setSaved] = useState<Captured | null>(null);

  useEffect(() => {
    api<{ items: Space[] }>("/api/auth/workspaces")
      .then((page) => {
        const writable = page.items.filter(
          (item) =>
            item.role === "OWNER" ||
            item.capabilities.includes("WRITE_PRIVATE"),
        );
        setSpaces(writable);
        let remembered = "";
        try {
          remembered = localStorage.getItem(LAST_SPACE) ?? "";
        } catch {
          // A blocked store only means choosing the space again.
        }
        setSpace(
          writable.some((item) => item.workspaceId === remembered)
            ? remembered
            : (writable[0]?.workspaceId ?? ""),
        );
      })
      .catch((failure) =>
        setError(
          failure instanceof ApiError && failure.status === 401
            ? "请先在这个浏览器登录 Poketto，再重新点书签。"
            : "暂时无法读取你的空间，请稍后再试。",
        ),
      );
  }, []);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!space || pending) return;
    const data = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    try {
      const result = await api<Captured>(
        "/api/admin/workspaces/" + space + "/capture",
        {
          method: "POST",
          body: {
            title: String(data.get("title") ?? ""),
            url: String(data.get("url") ?? ""),
            text: String(data.get("text") ?? ""),
            note: String(data.get("note") ?? ""),
          },
        },
      );
      try {
        localStorage.setItem(LAST_SPACE, space);
      } catch {
        // Remembering the space is a convenience only.
      }
      setSaved(result);
    } catch (failure) {
      setError(
        failure instanceof ApiError && failure.status === 429
          ? "收集得太频繁了，稍等一会儿再试。"
          : "没能存进口袋，请稍后再试。",
      );
    } finally {
      setPending(false);
    }
  }

  if (saved)
    return (
      <div className="capture-done" role="status">
        <p>已存入口袋：</p>
        <code>{saved.path}</code>
        <button type="button" onClick={() => window.close()}>
          关闭窗口
        </button>
      </div>
    );

  return (
    <form className="capture-form" onSubmit={save}>
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      <label>
        存到哪个空间
        <select
          value={space}
          onChange={(event) => setSpace(event.target.value)}
          disabled={!spaces?.length}
          required
        >
          {!spaces && <option value="">正在读取空间…</option>}
          {spaces?.length === 0 && <option value="">没有可以写入的空间</option>}
          {spaces?.map((item) => (
            <option key={item.workspaceId} value={item.workspaceId}>
              {item.displayName}
            </option>
          ))}
        </select>
      </label>
      <label>
        标题
        <input name="title" defaultValue={title} maxLength={200} />
      </label>
      <label>
        链接
        <input name="url" type="url" defaultValue={url} maxLength={2048} />
      </label>
      <label>
        摘录
        <textarea name="text" defaultValue={text} rows={5} maxLength={20000} />
      </label>
      <label>
        备注
        <textarea name="note" rows={3} maxLength={5000} autoFocus />
      </label>
      <button disabled={pending || !space}>
        {pending ? "正在保存…" : "存进口袋"}
      </button>
      <p className="muted">
        存在空间的 private/inbox/ 里，只有你和你授权的 AI 看得到。
      </p>
    </form>
  );
}
