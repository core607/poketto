"use client";
import { useEffect, useState } from "react";
import { api, ApiError, message } from "../lib/browser-api";
import type { AccountProfile } from "../lib/types";
import { Login } from "./login";
import { SiteAdministration } from "./site-administration";

/** Site operations do not depend on loading or selecting a workspace. */
export function SiteConsole() {
  const [account, setAccount] = useState<AccountProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  async function load() {
    setLoading(true);
    setError("");
    try {
      setAccount(await api<AccountProfile>("/api/auth/account"));
    } catch (failure) {
      setAccount(null);
      if (!(failure instanceof ApiError && failure.status === 401))
        setError(message(failure));
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
  }, []);
  return (
    <div className="page site-console">
      {loading ? (
        <p role="status">正在确认站点管理权限…</p>
      ) : error ? (
        <p role="alert">
          {error} <button onClick={() => void load()}>重试</button>
        </p>
      ) : !account ? (
        <Login onLogin={load} />
      ) : !account.account.siteAdministrator ? (
        <div className="empty">
          <h1>这里需要站点管理权限</h1>
          <a href="/account">返回我的账号</a>
        </div>
      ) : (
        <>
          <header className="page-head">
            <h1>站点管理</h1>
            <p>管理账号、创作者申请、公开空间和举报。</p>
          </header>
          <SiteAdministration account={account} />
        </>
      )}
    </div>
  );
}
