"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "../lib/browser-api";
import { message, type Identity } from "./admin";
import { AccountPanel, type AccountProfile } from "./account-panel";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import { useConfirmation } from "./confirmation";
import { WorkspaceProvider } from "./workspace-context";
import { Editor } from "./editor";
import { Members } from "./members";
import { Keys } from "./keys";
import { Connections } from "./connections";

export type SpaceSummary = {
  workspaceId: string;
  displayName: string;
  role: "OWNER" | "MEMBER";
  capabilities: string[];
};
const tabs = { content: "内容", account: "账号与空间", members: "成员与邀请", keys: "访问密钥", connections: "已连接应用" };
type Tab = keyof typeof tabs;

export function WorkspaceDashboard({ account, onLogout }: { account: AccountProfile; onLogout: () => Promise<void> }) {
  const confirm = useConfirmation();
  const page = useAdminPage<SpaceSummary>("/api/auth/workspaces");
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [selected, setSelected] = useState("");
  const [tab, setTab] = useState<Tab>("account");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const [dirty, setDirty] = useState(false);
  const initial = useRef(false);
  const generation = useRef(0);
  const currentUrl = useRef("");
  async function discard() {
    return !dirty || await confirm({ title: "放弃未保存的修改？", description: "请先保存当前文件，或放弃修改后继续。", confirmLabel: "放弃并继续" });
  }
  function writeUrl(workspace: string, nextTab: Tab, replace: boolean, retainPath: boolean) {
    const url = new URL(window.location.href);
    if (workspace) url.searchParams.set("workspace", workspace); else url.searchParams.delete("workspace");
    url.searchParams.set("tab", nextTab);
    if (!retainPath) url.searchParams.delete("path");
    currentUrl.current = url.pathname + url.search + url.hash;
    window.history[replace ? "replaceState" : "pushState"](window.history.state, "", currentUrl.current);
  }
  async function select(workspace: string, nextTab: Tab = "content", replace = false, retainPath = false) {
    const version = ++generation.current;
    setLoading(true);
    setError("");
    setDirty(false);
    setIdentity(null);
    setSelected(workspace);
    setTab(nextTab);
    writeUrl(workspace, nextTab, replace, retainPath);
    try {
      if (workspace) {
        const value = await api<Identity>(`/api/auth/workspaces/${encodeURIComponent(workspace)}/me`);
        if (version === generation.current) setIdentity(value);
      }
    } catch (failure) {
      if (version === generation.current) setError(message(failure));
    } finally {
      if (version === generation.current) setLoading(false);
    }
  }
  useEffect(() => {
    if (page.loading || page.error || initial.current) return;
    initial.current = true;
    const query = new URLSearchParams(window.location.search);
    const workspace = query.get("workspace") ?? page.items[0]?.workspaceId ?? "";
    const requested = query.get("tab") ?? (workspace ? "content" : "account");
    void select(workspace, requested in tabs ? requested as Tab : "account", true, true);
  }, [page.loading, page.error]);
  useEffect(() => {
    const restore = async () => {
      const previous = currentUrl.current;
      const query = new URLSearchParams(window.location.search);
      if (!(await discard())) {
        window.history.replaceState(window.history.state, "", previous);
        return;
      }
      const requested = query.get("tab") ?? "account";
      await select(query.get("workspace") ?? "", requested in tabs ? requested as Tab : "account", true, true);
    };
    window.addEventListener("popstate", restore);
    return () => window.removeEventListener("popstate", restore);
  }, [dirty]);
  const activeTab = !identity ? "account" : identity.role !== "OWNER" && !["content", "account"].includes(tab) ? "content" : tab;
  const content = <>
    <nav className="admin-tabs" aria-label="管理功能">
      {(Object.entries(tabs) as [Tab, string][]).filter(([key]) => key === "account" || identity && (key === "content" || identity.role === "OWNER"))
        .map(([key, label]) => <button key={key} aria-pressed={activeTab === key} onClick={async () => {
          if (!(await discard())) return;
          setDirty(false); setTab(key); writeUrl(selected, key, false, true);
        }}>{label}</button>)}
    </nav>
    {activeTab === "content" && identity && <Editor identity={identity} onDirtyChange={setDirty} />}
    {activeTab === "members" && <Members />}
    {activeTab === "keys" && identity && <Keys identity={identity} />}
    {activeTab === "connections" && <Connections />}
    {activeTab === "account" && <AccountPanel profile={account} hasWorkspace={page.total > 0}
      workspaceUnavailable={!!page.error} onBeforeJoin={discard} onJoined={async (workspaceId) => {
        setReceipt("已加入空间。你现在可以在这个空间中查看和整理获准访问的内容。");
        page.reload();
        await select(workspaceId, "content");
      }} />}
  </>;
  return <div className="admin-shell">
    <header className="admin-heading"><div><p className="eyebrow">{account.account.loginName} · 自己的工作台</p><h1>整理，续写。</h1></div>
      <button className="button-secondary" onClick={async () => { if (await discard()) await onLogout(); }}>退出登录</button>
    </header>
    <section className="sub-panel" aria-label="我的空间">
      <label>当前空间<select value={selected} disabled={loading} onChange={async (event) => { const value = event.target.value; if (await discard()) await select(value); }}>
        <option value="">账号与空间</option>
        {selected && !page.items.some((space) => space.workspaceId === selected) && <option value={selected}>{identity?.displayName ?? selected}</option>}
        {page.items.map((space) => <option key={space.workspaceId} value={space.workspaceId}>{space.displayName}</option>)}
      </select></label>
      <AdminPagination label="空间" page={page} disabled={loading} />
    </section>
    {receipt && <p className="notice" role="status">{receipt}</p>}
    {error && <p className="notice danger" role="alert">{error} <button onClick={() => void select(selected, tab, true, true)}>重新读取空间</button></p>}
    {loading ? <p role="status">正在打开空间…</p> : identity ? <WorkspaceProvider key={identity.workspaceId} workspaceId={identity.workspaceId}>{content}</WorkspaceProvider> : content}
  </div>;
}
