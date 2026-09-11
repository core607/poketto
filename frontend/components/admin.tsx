"use client";
import { FormEvent, useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { Editor } from "./editor";
import { Members } from "./members";
import { Keys } from "./keys";
import { Connections } from "./connections";
import { ConfirmationProvider, useConfirmation } from "./confirmation";
import { AccountPanel, type AccountProfile } from "./account-panel";

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
  return (
    <ConfirmationProvider>
      <AdminContent />
    </ConfirmationProvider>
  );
}
function AdminContent() {
  const confirm = useConfirmation();
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [account, setAccount] = useState<AccountProfile | null>(null);
  const [workspaceUnavailable, setWorkspaceUnavailable] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [tab, setTab] = useState("content");
  const activeTab =
    !identity || tab === "account"
      ? "account"
      : identity.role === "OWNER"
        ? tab
        : "content";
  const [dirty, setDirty] = useState(false);
  async function refresh() {
    setLoading(true);
    setError("");
    setWorkspaceUnavailable(false);
    try {
      setAccount(await api<AccountProfile>("/api/auth/account"));
      try {
        setIdentity(await api<Identity>("/api/auth/me"));
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) throw error;
        setIdentity(null);
        if (!(error instanceof ApiError && error.status === 403)) {
          setWorkspaceUnavailable(true);
          setError("空间暂时无法读取，请稍后重试。你的账号仍已登录。");
        }
      }
    } catch (error) {
      setIdentity(null);
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
    if (
      dirty &&
      !(await confirm({
        title: "放弃修改并退出登录？",
        description: "还有未保存的修改。退出后，这些修改将丢失。",
        confirmLabel: "放弃并退出",
      }))
    )
      return;
    try {
      await api("/api/auth/logout", { method: "POST" });
      setIdentity(null);
      setAccount(null);
      setTab("content");
      setDirty(false);
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
  if (!account)
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
          <p className="eyebrow">{account.account.loginName} · 自己的工作台</p>
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
      {workspaceUnavailable && (
        <button className="button-secondary" onClick={refresh}>
          重新读取空间
        </button>
      )}
      <nav className="admin-tabs" aria-label="管理功能">
        {identity && (
          <button
            aria-pressed={activeTab === "content"}
            onClick={() => setTab("content")}
          >
            内容
          </button>
        )}
        <button
          aria-pressed={activeTab === "account"}
          onClick={() => setTab("account")}
        >
          账号与空间
        </button>
        {identity?.role === "OWNER" && (
          <>
            <button
              aria-pressed={activeTab === "members"}
              onClick={() => setTab("members")}
            >
              成员与邀请
            </button>
            <button
              aria-pressed={activeTab === "keys"}
              onClick={() => setTab("keys")}
            >
              访问密钥
            </button>
            <button
              aria-pressed={activeTab === "connections"}
              onClick={() => setTab("connections")}
            >
              已连接应用
            </button>
          </>
        )}
      </nav>
      {identity && (
        <div hidden={activeTab !== "content"}>
          <Editor identity={identity} onDirtyChange={setDirty} />
        </div>
      )}
      {activeTab === "account" && (
        <AccountPanel
          profile={account}
          hasWorkspace={!!identity}
          workspaceUnavailable={workspaceUnavailable}
          onBeforeJoin={async () =>
            !dirty ||
            (await confirm({
              title: "放弃未保存的修改并加入空间？",
              description: "请先保存当前文件，或放弃这些修改后继续。",
              confirmLabel: "放弃并继续",
            }))
          }
          onJoined={async () => {
            setDirty(false);
            await refresh();
          }}
        />
      )}
      {activeTab === "members" && <Members />}
      {activeTab === "connections" && <Connections />}
      {activeTab === "keys" && identity && <Keys identity={identity} />}
    </div>
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
      if (mode === "register" && password !== String(form.get("confirmation")))
        throw new ApiError(400, "两次密码不一致，请重新确认。");
      if (mode === "register") {
        await api("/api/auth/register", {
          method: "POST",
          body: { token: String(form.get("token")), login, password },
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
      <p className="eyebrow">欢迎回来</p>
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
