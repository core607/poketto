"use client";
import { useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { ConfirmationProvider } from "./confirmation";
import { type AccountProfile } from "./account-panel";
import { WorkspaceDashboard } from "./workspace-dashboard";
import { Login } from "./login";
import { clearAccountDrafts, withDraftStorage } from "../lib/local-drafts";
export { Login } from "./login";

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
      if (account) {
        try {
          await withDraftStorage((storage) =>
            clearAccountDrafts(storage, account.account.accountId),
          );
        } catch {
          setError(
            "已退出登录，但本机草稿未能清理；可在浏览器设置中清除本站数据。",
          );
        }
      }
      window.history.replaceState(
        window.history.state,
        "",
        window.location.pathname,
      );
      setAccount(null);
    } catch (error) {
      setError(message(error));
    }
  }
  if (loading)
    return (
      <p role="status" className="page muted">
        正在确认会话…
      </p>
    );
  const alert = error && (
    <p role="alert" className="notice danger">
      {error}
    </p>
  );
  return account ? (
    <>
      <WorkspaceDashboard account={account} onLogout={logout} />
      {alert}
    </>
  ) : (
    <div className="page">
      <Login onLogin={refresh} />
      {alert}
    </div>
  );
}
