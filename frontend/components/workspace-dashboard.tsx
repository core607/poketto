"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../lib/browser-api";
import { message, type Identity } from "./admin";
import { AccountPanel, type AccountProfile } from "./account-panel";
import { useAdminPage } from "./admin-pagination";
import { useConfirmation } from "./confirmation";
import { WorkspaceProvider } from "./workspace-context";
import { Editor } from "./editor";
import { Members } from "./members";
import { Keys } from "./keys";
import { CaptureSetup } from "./capture-setup";
import { Connections } from "./connections";
import { RepositoryConnection } from "./repository-connection";
import { SpacePublication } from "./space-publication";
import type { ContentLocation } from "../lib/repository-navigation";
import { SiteAdministration } from "./site-administration";
import { SpaceSwitcher } from "./space-switcher";
import { CorrectionReview } from "./correction-review";
import { Avatar, Icon, type IconName } from "./ui/icons";

export type SpaceSummary = {
  workspaceId: string;
  displayName: string;
  role: "OWNER" | "MEMBER";
  capabilities: string[];
};
const tabs = {
  content: "内容",
  account: "账号",
  members: "成员与邀请",
  keys: "AI 助手",
  connections: "AI 助手",
  repository: "存储位置",
  publication: "网站",
  corrections: "读者勘误",
  site: "站务",
};
/** One plain sentence per section: what it is for, without repository vocabulary. */
const descriptions: Record<keyof typeof tabs, string> = {
  content:
    "写笔记、整理分类和图片。保存会写进这个空间的仓库，发布后出现在网站上。",
  account: "你的登录方式和昵称，以及新建或加入空间。",
  members: "邀请别人一起整理这个空间，并决定每个人能看、能改哪些内容。",
  keys: "让 Claude、ChatGPT 等 AI 助手读写这个空间，和你一起整理。",
  connections: "让 Claude、ChatGPT 等 AI 助手读写这个空间，和你一起整理。",
  repository:
    "这个空间的内容存放在一个 Git 仓库里，可以把它理解成会记住每次修改的云端文件夹。这里显示它连到哪里、是否正常。",
  publication:
    "决定这个空间要不要公开成网站，以及网站上显示的名字、简介和署名。",
  corrections:
    "读者在公开文章上提的修改建议。看过对比再决定，采纳后才会改动正文。",
  site: "站点管理员专用：调整账号的站点权限、审阅公开内容、处理举报。",
};
type Tab = keyof typeof tabs;
const icons: Record<Tab, IconName> = {
  content: "file",
  account: "user",
  members: "users",
  keys: "key",
  connections: "link",
  repository: "branch",
  publication: "globe",
  corrections: "pen",
  site: "shield",
};
const spaceSections: Tab[] = [
  "content",
  "publication",
  "corrections",
  "members",
  "connections",
  "repository",
];

function historyPosition() {
  const position: unknown = window.history.state?.pokettoAdminPosition;
  return typeof position === "number" &&
    Number.isSafeInteger(position) &&
    position >= 0
    ? position
    : null;
}

/** How to connect an AI client; the address is this installation's MCP endpoint. */
function McpGuide() {
  const [origin, setOrigin] = useState("");
  useEffect(() => setOrigin(window.location.origin), []);
  return (
    <section className="sub-panel mcp-guide" aria-labelledby="mcp-guide">
      <div className="panel-heading">
        <h2 id="mcp-guide">连接一个 AI 助手</h2>
      </div>
      <ol>
        <li>
          在 Claude、ChatGPT 等支持 MCP 的 AI
          客户端里，找到「添加连接器」或「自定义 MCP」。
        </li>
        <li>
          填入这个地址：<code>{origin ? origin + "/mcp" : "/mcp"}</code>
        </li>
        <li>
          按提示登录
          Poketto，选择这个空间和允许的权限。连接成功后会出现在下面的列表里，随时可以撤销。
        </li>
      </ol>
    </section>
  );
}

export function WorkspaceDashboard({
  account,
  onLogout,
}: {
  account: AccountProfile;
  onLogout: () => Promise<void>;
}) {
  const confirm = useConfirmation();
  const page = useAdminPage<SpaceSummary>("/api/auth/workspaces");
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [displayName, setDisplayName] = useState(account.account.displayName);
  const [selected, setSelected] = useState("");
  const [tab, setTab] = useState<Tab>("account");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const [dirty, setDirty] = useState(false);
  const discardDraft = useRef<(() => void) | undefined>(undefined);
  const editorDirty = useCallback((value: boolean, discard?: () => void) => {
    setDirty(value);
    discardDraft.current = discard;
  }, []);
  const initial = useRef(false);
  const generation = useRef(0);
  const currentUrl = useRef("");
  const acceptedPosition = useRef(0);
  const returningPosition = useRef<number | null>(null);
  const confirmingNavigation = useRef(false);
  async function discard() {
    const accepted =
      !dirty ||
      (await confirm({
        title: "放弃未保存的修改？",
        description:
          "请先保存当前文件并等待图片上传完成，或放弃修改后继续。离开后，仍在上传的图片不会插入其他文件。",
        confirmLabel: "放弃并继续",
      }));
    if (accepted && dirty) discardDraft.current?.();
    return accepted;
  }
  function writeUrl(
    workspace: string,
    nextTab: Tab,
    replace: boolean,
    retainPath: boolean,
    contentLocation?: ContentLocation,
  ) {
    const url = new URL(window.location.href);
    if (workspace) url.searchParams.set("workspace", workspace);
    else url.searchParams.delete("workspace");
    url.searchParams.set("tab", nextTab);
    if (!retainPath) {
      url.searchParams.delete("path");
      url.searchParams.delete("folder");
    }
    if (contentLocation) {
      for (const key of ["path", "folder"] as const) {
        if (contentLocation[key])
          url.searchParams.set(key, contentLocation[key]);
        else url.searchParams.delete(key);
      }
    }
    const nextUrl = url.pathname + url.search + url.hash;
    const replaceEntry = replace || nextUrl === currentUrl.current;
    const position = replaceEntry
      ? (historyPosition() ?? acceptedPosition.current)
      : acceptedPosition.current + 1;
    currentUrl.current = nextUrl;
    acceptedPosition.current = position;
    window.history[replaceEntry ? "replaceState" : "pushState"](
      { ...window.history.state, pokettoAdminPosition: position },
      "",
      currentUrl.current,
    );
  }
  async function select(
    workspace: string,
    nextTab: Tab = "content",
    replace = false,
    retainPath = false,
  ) {
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
        const value = await api<Identity>(
          `/api/auth/workspaces/${encodeURIComponent(workspace)}/me`,
        );
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
    const workspace =
      query.get("workspace") ?? page.items[0]?.workspaceId ?? "";
    const requested = query.get("tab") ?? (workspace ? "content" : "account");
    void select(
      workspace,
      requested in tabs ? (requested as Tab) : "account",
      true,
      true,
    );
  }, [page.loading, page.error]);
  useEffect(() => {
    let active = true;
    const restore = async () => {
      const position = historyPosition();
      if (returningPosition.current !== null) {
        if (position === returningPosition.current)
          returningPosition.current = null;
        else if (position !== null)
          window.history.go(returningPosition.current - position);
        return;
      }
      if (confirmingNavigation.current) return;
      confirmingNavigation.current = true;
      const accepted = await discard();
      confirmingNavigation.current = false;
      if (!active) return;
      if (!accepted) {
        const reachedPosition = historyPosition();
        if (reachedPosition !== null) {
          const delta = acceptedPosition.current - reachedPosition;
          if (delta) {
            returningPosition.current = acceptedPosition.current;
            window.history.go(delta);
          }
        } else {
          // Entries outside this dashboard have no known traversal distance.
          // Preserve the reached entry instead of replacing its destination.
          window.history.pushState(
            {
              ...window.history.state,
              pokettoAdminPosition: acceptedPosition.current,
            },
            "",
            currentUrl.current,
          );
        }
        return;
      }
      const query = new URLSearchParams(window.location.search);
      const requested = query.get("tab") ?? "account";
      await select(
        query.get("workspace") ?? "",
        requested in tabs ? (requested as Tab) : "account",
        true,
        true,
      );
    };
    window.addEventListener("popstate", restore);
    return () => {
      active = false;
      window.removeEventListener("popstate", restore);
    };
  }, [dirty]);
  const administrator = account.account.siteAdministrator;
  const activeTab: Tab =
    tab === "site" && administrator
      ? "site"
      : !identity
        ? "account"
        : identity.role !== "OWNER" &&
            !["content", "account", "connections"].includes(tab) &&
            !(
              tab === "corrections" && identity.capabilities.includes("PUBLISH")
            )
          ? "content"
          : tab;
  const available = (key: Tab) =>
    key === "account" ||
    (key === "site" && administrator) ||
    (identity !== null &&
      key !== "site" &&
      (key === "content" ||
        key === "connections" ||
        (key === "corrections" && identity.capabilities.includes("PUBLISH")) ||
        identity.role === "OWNER"));
  const navItem = (key: Tab) =>
    available(key) && (
      <button
        key={key}
        type="button"
        className="studio-nav-item"
        aria-current={
          activeTab === key || (key === "connections" && activeTab === "keys")
            ? "page"
            : undefined
        }
        onClick={async () => {
          if (!(await discard())) return;
          setDirty(false);
          setTab(key);
          writeUrl(selected, key, false, true);
        }}
      >
        <Icon name={icons[key]} />
        {tabs[key]}
      </button>
    );
  const summary = page.items.find((space) => space.workspaceId === selected);
  const current =
    summary ??
    (identity
      ? { displayName: identity.displayName ?? selected, role: identity.role }
      : undefined);
  const content = (
    <>
      {activeTab === "content" && identity && (
        <Editor
          identity={identity}
          onDirtyChange={editorDirty}
          onNavigate={(location, replace = false) =>
            writeUrl(selected, "content", replace, true, location)
          }
        />
      )}
      {activeTab === "members" && <Members />}
      {(activeTab === "connections" || activeTab === "keys") && (
        <div className="management-panel">
          <McpGuide />
          <Connections />
          {identity && <CaptureSetup identity={identity} />}
          {identity?.role === "OWNER" && (
            <details
              className="sub-panel advanced"
              open={activeTab === "keys" || undefined}
            >
              <summary>访问密钥（高级）</summary>
              <p className="muted">
                给无法登录授权的脚本或工具使用。每个工具一把钥匙，可随时撤销。
              </p>
              <Keys identity={identity} />
            </details>
          )}
        </div>
      )}
      {activeTab === "repository" && identity?.role === "OWNER" && (
        <RepositoryConnection
          key={identity.workspaceId}
          workspaceId={identity.workspaceId}
        />
      )}
      {activeTab === "publication" && identity?.role === "OWNER" && (
        <SpacePublication
          key={identity.workspaceId}
          workspaceId={identity.workspaceId}
          onRenamed={page.reload}
        />
      )}
      {activeTab === "corrections" &&
        identity?.capabilities.includes("PUBLISH") && (
          <CorrectionReview
            key={identity.workspaceId}
            workspaceId={identity.workspaceId}
          />
        )}
      {activeTab === "account" && (
        <AccountPanel
          profile={account}
          onDisplayName={setDisplayName}
          hasWorkspace={page.total > 0}
          workspaceUnavailable={!!page.error}
          onBeforeJoin={discard}
          onJoined={async (workspaceId, created) => {
            setReceipt(
              created
                ? "空间已创建，公开网站默认关闭。"
                : "已加入空间。你现在可以在这个空间中查看和整理获准访问的内容。",
            );
            page.reload();
            await select(workspaceId, "content");
          }}
        />
      )}
      {activeTab === "site" && administrator && (
        <SiteAdministration account={account} />
      )}
    </>
  );
  return (
    <div className="studio">
      <aside className="studio-side">
        <SpaceSwitcher
          page={page}
          selected={selected}
          current={current}
          disabled={loading}
          onSelect={async (workspaceId) => {
            if (await discard()) await select(workspaceId);
          }}
          onManage={async () => {
            if (!(await discard())) return;
            setDirty(false);
            setTab("account");
            writeUrl(selected, "account", false, true);
          }}
        />
        <nav className="studio-nav" aria-label="管理功能">
          {identity && (
            <>
              <p className="studio-nav-label">这个空间</p>
              {spaceSections.map(navItem)}
            </>
          )}
          <p className="studio-nav-label">账号</p>
          {navItem("account")}
          {navItem("site")}
        </nav>
        <div className="studio-side-foot">
          <span className="studio-user">
            <Avatar name={displayName} />
            <span>{displayName}</span>
          </span>
          <button
            type="button"
            className="link-btn"
            onClick={async () => {
              if (await discard()) await onLogout();
            }}
          >
            退出登录
          </button>
        </div>
      </aside>
      <section className="studio-main" aria-label={tabs[activeTab]}>
        <header className="studio-head">
          <h1>{tabs[activeTab]}</h1>
          <p>{descriptions[activeTab]}</p>
        </header>
        {receipt && (
          <p className="notice success" role="status">
            {receipt}
          </p>
        )}
        {error && (
          <p className="notice danger" role="alert">
            {error}{" "}
            <button
              type="button"
              className="link-btn"
              onClick={() => void select(selected, tab, true, true)}
            >
              重新读取空间
            </button>
          </p>
        )}
        {loading ? (
          <p role="status" className="muted">
            正在打开空间…
          </p>
        ) : identity ? (
          <WorkspaceProvider
            key={identity.workspaceId}
            workspaceId={identity.workspaceId}
          >
            {content}
          </WorkspaceProvider>
        ) : (
          content
        )}
      </section>
    </div>
  );
}
