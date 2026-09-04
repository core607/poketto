"use client";
import { FormEvent, useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { Editor } from "./editor";
import { Members } from "./members";
import { Keys } from "./keys";

export type Identity = {
  accountId: string;
  workspaceId: string;
  role: "OWNER" | "MEMBER";
  capabilities: string[];
};
export function message(error: unknown) {
  return error instanceof ApiError
    ? error.message
    : "操作未能完成，请检查连接后重试。";
}
export function Admin() {
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [tab, setTab] = useState("content");
  const [dirty, setDirty] = useState(false);
  async function refresh() {
    setLoading(true);
    try {
      setIdentity(await api<Identity>("/api/auth/me"));
      setError("");
    } catch (error) {
      setIdentity(null);
      if (!(error instanceof ApiError && error.status === 401))
        setError(message(error));
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void refresh();
  }, []);
  async function logout() {
    if (dirty && !window.confirm("还有未保存的修改。确认放弃并退出？")) return;
    try {
      await api("/api/auth/logout", { method: "POST" });
      setIdentity(null);
    } catch (error) {
      setError(message(error));
    }
  }
  if (loading)
    return (
      <div className="empty-state">
        <p>正在确认会话…</p>
      </div>
    );
  if (!identity)
    return (
      <div>
        <Login onLogin={refresh} />
        {error && (
          <p role="alert" className="notice danger">
            {error}
          </p>
        )}
      </div>
    );
  return (
    <div className="admin-shell">
      <header className="admin-heading">
        <div>
          <p className="eyebrow">自己的工作台</p>
          <h1>整理，续写。</h1>
        </div>
        <button className="button-secondary" onClick={logout}>
          退出登录
        </button>
      </header>
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      <nav className="admin-tabs" aria-label="管理功能">
        <button
          aria-pressed={tab === "content"}
          onClick={() => setTab("content")}
        >
          内容
        </button>
        {identity.role === "OWNER" && (
          <>
            <button
              aria-pressed={tab === "members"}
              onClick={() => setTab("members")}
            >
              成员与邀请
            </button>
            <button
              aria-pressed={tab === "keys"}
              onClick={() => setTab("keys")}
            >
              访问密钥
            </button>
          </>
        )}
      </nav>
      <div hidden={tab !== "content"}>
        <Editor identity={identity} onDirtyChange={setDirty} />
      </div>
      {tab === "members" && <Members />}
      {tab === "keys" && <Keys identity={identity} />}
    </div>
  );
}

function Login({ onLogin }: { onLogin: () => Promise<void> }) {
  const [mode, setMode] = useState("login");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    try {
      const login = String(form.get("login"));
      const password = String(form.get("password"));
      if (
        ["initialize", "register"].includes(mode) &&
        password !== String(form.get("confirmation"))
      )
        throw new ApiError(400, "两次密码不一致，请重新确认。");
      if (mode === "initialize")
        await api("/api/auth/initialize", {
          method: "POST",
          body: {
            initializationToken: String(form.get("token")),
            login,
            password,
          },
        });
      if (mode === "register")
        await api("/api/auth/invitations/register", {
          method: "POST",
          body: { token: String(form.get("token")), login, password },
        });
      await api("/api/auth/login", {
        method: "POST",
        form: new URLSearchParams({ username: login, password }),
      });
      if (mode === "accept")
        await api("/api/auth/invitations/accept", {
          method: "POST",
          body: { token: String(form.get("token")) },
        });
      await onLogin();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <section className="login-card">
      <p className="eyebrow">欢迎回来</p>
      <h1>
        {mode === "login"
          ? "打开自己的空间。"
          : mode === "initialize"
            ? "第一次，安顿下来。"
            : "受邀来到这里。"}
      </h1>
      <p className="muted">登录后，继续整理你的记录与收藏。</p>
      <form onSubmit={submit} key={mode}>
        {mode !== "login" && (
          <label>
            {mode === "initialize" ? "初始化凭证" : "邀请凭证"}
            <input
              name="token"
              type="password"
              required
              autoComplete="off"
              maxLength={256}
            />
          </label>
        )}
        <label>
          用户名
          <input
            name="login"
            required
            autoComplete="username"
            minLength={3}
            maxLength={64}
          />
        </label>
        <label>
          密码
          <input
            name="password"
            type="password"
            required
            autoComplete={
              mode === "login" || mode === "accept"
                ? "current-password"
                : "new-password"
            }
            minLength={mode === "login" || mode === "accept" ? undefined : 12}
            maxLength={256}
          />
        </label>
        {["initialize", "register"].includes(mode) && (
          <>
            <p className="muted form-help">
              用户名为 3–64
              位英文字母、数字、点、下划线或连字符，首位为字母或数字。密码为
              12–256 位。
            </p>
            <label>
              确认密码
              <input
                name="confirmation"
                type="password"
                required
                autoComplete="new-password"
                minLength={12}
                maxLength={256}
              />
            </label>
          </>
        )}
        {error && (
          <p className="notice danger" role="alert">
            {error}
          </p>
        )}
        <button disabled={pending}>
          {pending
            ? "正在处理…"
            : mode === "login"
              ? "登录 →"
              : mode === "accept"
                ? "登录并接受邀请 →"
                : "创建账号并登录 →"}
        </button>
      </form>
      <div className="login-options">
        <button
          className="text-button"
          onClick={() => {
            setMode("accept");
            setError("");
          }}
        >
          已有账号接受邀请
        </button>
        <button
          className="text-button"
          onClick={() => {
            setMode(mode === "initialize" ? "login" : "initialize");
            setError("");
          }}
        >
          首次初始化
        </button>
        <button
          className="text-button"
          onClick={() => {
            setMode(mode === "register" ? "login" : "register");
            setError("");
          }}
        >
          使用邀请注册
        </button>
        {mode !== "login" && (
          <button className="text-button" onClick={() => setMode("login")}>
            已有账号
          </button>
        )}
      </div>
    </section>
  );
}
