"use client";
import { FormEvent, useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { ConfirmationProvider } from "./confirmation";
import { type AccountProfile } from "./account-panel";
import { WorkspaceDashboard } from "./workspace-dashboard";

export type Identity = {
  accountId: string;
  displayName?: string;
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
  return (
    <ConfirmationProvider>
      <AdminContent />
    </ConfirmationProvider>
  );
}
function AdminContent() {
  const [account, setAccount] = useState<AccountProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  async function refresh() {
    setLoading(true);
    setError("");
    try {
      setAccount(await api<AccountProfile>("/api/auth/account"));
    } catch (error) {
      setAccount(null);
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
    try {
      await api("/api/auth/logout", { method: "POST" });
      setAccount(null);
    } catch (error) {
      setError(message(error));
    }
  }
  if (loading) return <p role="status">正在确认会话…</p>;
  return (
    <>
      {account ? (
        <WorkspaceDashboard account={account} onLogout={logout} />
      ) : (
        <Login onLogin={refresh} />
      )}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </>
  );
}

export function Login({
  onLogin,
  connection = false,
}: {
  onLogin: () => Promise<void>;
  connection?: boolean;
}) {
  const [mode, setMode] = useState("login");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [invitation, setInvitation] = useState("");
  const [createdLogin, setCreatedLogin] = useState("");
  useEffect(() => {
    if (connection) return;
    const value = new URLSearchParams(window.location.hash.slice(1)).get(
      "register",
    );
    if (value !== null) {
      window.history.replaceState(
        window.history.state,
        "",
        window.location.pathname + window.location.search,
      );
      setMode("register");
      if (value.length <= 256) setInvitation(value);
    }
  }, [connection]);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    try {
      const login = String(form.get("login"));
      const password = String(form.get("password"));
      const token = String(form.get("token") ?? "").trim();
      if (mode === "register" && token.startsWith("invite_"))
        throw new ApiError(
          400,
          "这是加入空间的邀请码。请先使用注册邀请码创建账号，登录后再到“账号与空间”加入空间。",
        );
      if (mode === "register" && password !== String(form.get("confirmation")))
        throw new ApiError(400, "两次密码不一致，请重新确认。");
      if (mode === "register") {
        await api("/api/auth/register", {
          method: "POST",
          body: { token, login, password },
        });
        setCreatedLogin(login);
        setMode("login");
        setInvitation("");
      }
      await api("/api/auth/login", {
        method: "POST",
        form: new URLSearchParams({ username: login, password }),
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
      <p className="eyebrow">
        {mode === "login" ? "欢迎回来" : "开始记录与收藏"}
      </p>
      <h1>{mode === "login" ? "登录 Poketto" : "创建账号"}</h1>
      <p className="muted">
        {mode === "login"
          ? "继续整理你的记录与收藏。"
          : "填写注册邀请码。注册后可在管理页加入受邀的空间。"}
      </p>
      {createdLogin && (
        <p role="status">账号已创建。若尚未登录，请使用新账号登录。</p>
      )}
      <form onSubmit={submit} key={mode}>
        {mode !== "login" && (
          <label>
            注册邀请码
            <input
              name="token"
              type="text"
              value={invitation}
              onChange={(event) => setInvitation(event.target.value)}
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
            defaultValue={createdLogin}
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
              mode === "login" ? "current-password" : "new-password"
            }
            minLength={mode === "login" ? undefined : 12}
            maxLength={256}
          />
        </label>
        {mode === "register" && (
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
          {pending ? "正在处理…" : mode === "login" ? "登录 →" : "注册并登录 →"}
        </button>
      </form>
      {!connection && (
        <div className="login-options">
          <button
            className="text-button"
            disabled={pending}
            onClick={() => {
              setMode(mode === "register" ? "login" : "register");
              setError("");
            }}
          >
            {mode === "register" ? "已有账号，去登录" : "注册"}
          </button>
        </div>
      )}
    </section>
  );
}
