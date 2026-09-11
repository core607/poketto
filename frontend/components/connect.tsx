"use client";
import { useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { Login, message } from "./admin";
import type { AccountProfile } from "./account-panel";
import type { SpaceSummary } from "./workspace-dashboard";

export const scopeLabels: Record<string, { label: string; detail: string }> = {
  "repository:execute": {
    label: "运行隔离命令",
    detail:
      "在隔离工作区中检索和分析内容；能否读取私密内容、保存修改取决于下面的权限。",
  },
  "content:read_private": {
    label: "读取私密内容",
    detail: "读取私密笔记、媒体和完整 Git 历史。",
  },
  "content:write_private": {
    label: "修改私密内容",
    detail: "保存、移动或删除私密文件，并上传媒体。",
  },
  "content:publish": {
    label: "发布与修改公开内容",
    detail: "允许发布、更新或删除公开内容，包括将私密内容公开。",
  },
  offline_access: {
    label: "保持连接",
    detail: "允许自动续期；可随时在后台断开连接。",
  },
};
type Consent = { clientName: string; redirectUri: string; scopes: string[] };
export function Connect() {
  const [request, setRequest] = useState("");
  const [account, setAccount] = useState<AccountProfile | null>(null);
  const [spaces, setSpaces] = useState<SpaceSummary[]>([]);
  const [workspace, setWorkspace] = useState("");
  const [offset, setOffset] = useState(0);
  const [total, setTotal] = useState(0);
  const [consent, setConsent] = useState<Consent | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function load(id: string, pageOffset = 0) {
    setLoading(true);
    setError("");
    try {
      setAccount(await api<AccountProfile>("/api/auth/account"));
      const value = await api<Consent>(
        "/api/auth/oauth/consent?request=" + encodeURIComponent(id),
      );
      setConsent(value);
      const page = await api<{ items: SpaceSummary[]; total: number }>(`/api/auth/workspaces?offset=${pageOffset}&limit=30`);
      setSpaces(page.items);
      setTotal(page.total);
      setOffset(pageOffset);
      setWorkspace(page.items.find((space) => space.role === "OWNER")?.workspaceId ?? "");
      setSelected(
        value.scopes.filter(
          (s) => s === "repository:execute" || s === "offline_access",
        ),
      );
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setAccount(null);
      else setError("授权请求已失效或不可用，请回到客户端重新连接。");
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get("request") ?? "";
    setRequest(id);
    if (!id) {
      setError("请从需要连接的客户端发起授权。");
      setLoading(false);
      return;
    }
    void load(id);
  }, []);
  async function decide(allow: boolean) {
    setPending(true);
    setError("");
    try {
      const result = await api<{ redirect: string }>(
        "/api/auth/oauth/consent",
        { method: "POST", body: { request, workspaceId: workspace || null, scopes: selected, allow } },
      );
      // The server binds this exact redirect to the browser's validated authorization request.
      window.location.assign(result.redirect);
    } catch (e) {
      setError(message(e));
      setPending(false);
    }
  }
  return (
    <section className="oauth-shell" aria-labelledby="connect-title">
      <p className="eyebrow">连接你的空间</p>
      <h1 id="connect-title">授权应用访问 Poketto</h1>
      {loading ? (
        <p role="status">正在确认连接…</p>
      ) : !account && request && !error ? (
        <>
          <p>先登录，再选择允许应用使用的权限。登录不会自动授权。</p>
          <Login connection onLogin={() => load(request)} />
        </>
      ) : (
        consent && (
          <>
            <h2>{consent.clientName}</h2>
            <p>客户端登记的名称，未经身份认证。请确认这是你刚刚发起的连接。</p>
            <p>
              授权后返回：
              <strong style={{ overflowWrap: "anywhere" }}>
                {consent.redirectUri}
              </strong>
            </p>
            <label>连接哪个空间？<select value={workspace} disabled={pending} onChange={(event) => setWorkspace(event.target.value)}>
              <option value="">请选择空间</option>
              {spaces.map((space) => <option key={space.workspaceId} value={space.workspaceId} disabled={space.role !== "OWNER"}>{space.displayName}{space.role !== "OWNER" ? "（需要所有者授权）" : ""}</option>)}
            </select></label>
            {total > 30 && <nav className="pagination" aria-label="空间分页">
              <button disabled={pending || offset === 0} onClick={() => void load(request, Math.max(0, offset - 30))}>上一页</button>
              <button disabled={pending || offset + 30 >= total} onClick={() => void load(request, offset + 30)}>下一页</button>
            </nav>}
            {!workspace && <p>先<a href="/admin?tab=account" target="_blank" rel="noreferrer">创建或加入空间</a>，再回来继续授权。<button className="text-button" disabled={pending} onClick={() => void load(request)}>重新读取空间</button></p>}
            <fieldset disabled={pending}>
              <legend>允许哪些操作？</legend>
              {Object.entries(scopeLabels)
                .filter(([scope]) => consent.scopes.includes(scope))
                .map(([scope, text]) => (
                  <label key={scope} className="oauth-permission">
                    <input
                      type="checkbox"
                      checked={selected.includes(scope)}
                      onChange={(e) =>
                        setSelected((values) =>
                          e.target.checked
                            ? [...values, scope]
                            : values.filter((value) => value !== scope),
                        )
                      }
                    />
                    <span>
                      <strong>{text.label}</strong>
                      <span>{text.detail}</span>
                    </span>
                  </label>
                ))}
            </fieldset>
            <p>未勾选的权限不会授予。可在管理页面的“已连接应用”中断开连接。</p>
            <div className="oauth-actions">
              <button
                disabled={
                  pending || !workspace || !selected.some((s) => s !== "offline_access")
                }
                onClick={() => void decide(true)}
              >
                {pending ? "正在处理…" : "允许连接"}
              </button>{" "}
              <button
                className="button-secondary"
                disabled={pending}
                onClick={() => void decide(false)}
              >
                拒绝
              </button>
            </div>
          </>
        )
      )}
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
