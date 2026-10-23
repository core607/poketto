"use client";
import { useEffect, useState } from "react";
import { api, ApiError, message } from "../lib/browser-api";
import { ConfirmationProvider } from "./confirmation";
import type { AccountProfile } from "../lib/types";
import { WorkspaceDashboard } from "./workspace-dashboard";
import { Login } from "./login";
import { clearAccountDrafts, withDraftStorage } from "../lib/local-drafts";
import { returnPath } from "../lib/return-path";

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
  const [returning, setReturning] = useState(false);
  async function refresh() {
    setLoading(true);
    setError("");
    try {
      const profile = await api<AccountProfile>("/api/auth/account");
      // A reader who signed in from another page goes back to it.
      const next = returnPath(
        new URLSearchParams(window.location.search).get("next"),
      );
      if (next) {
        setReturning(true);
        window.location.assign(next);
        return;
      }
      setAccount(profile);
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
      window.dispatchEvent(new Event("poketto:session"));
    } catch (error) {
      setError(message(error));
    }
  }
  if (loading || returning)
    return (
      <p role="status" className="page muted">
        {returning ? "已登录，正在返回原来的页面…" : "正在确认会话…"}
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
