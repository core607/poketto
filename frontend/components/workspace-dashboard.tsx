"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, message } from "../lib/browser-api";
import {
  canCreateSpaces,
  siteGroupAbilities,
  siteGroups,
} from "../lib/site-groups";
import type { AccountProfile, Identity } from "../lib/types";
import { JoinSpace, SpacesPanel } from "./spaces-panel";
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
  spaces: "新建或加入空间",
  members: "成员与邀请",
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
  spaces: "新建自己的空间，或者用邀请码加入别人的空间。",
  members: "邀请别人一起整理这个空间，并决定每个人能看、能改哪些内容。",
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
  spaces: "plus",
  members: "users",
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
  const [started, setStarted] = useState(false);
  const [selected, setSelected] = useState("");
  const [tab, setTab] = useState<Tab>("spaces");
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
    const requested = query.get("tab") ?? (workspace ? "content" : "spaces");
    void select(
      workspace,
      requested in tabs ? (requested as Tab) : "spaces",
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
      const requested = query.get("tab") ?? "spaces";
      await select(
        query.get("workspace") ?? "",
        requested in tabs ? (requested as Tab) : "spaces",
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
        ? "spaces"
        : identity.role !== "OWNER" &&
            !["content", "spaces", "connections"].includes(tab) &&
            !(
              tab === "corrections" && identity.capabilities.includes("PUBLISH")
            )
          ? "content"
          : tab;
  const available = (key: Tab) =>
    key === "spaces" ||
    (key === "site" && administrator) ||
    (identity !== null &&
      key !== "site" &&
      (key === "content" ||
        key === "connections" ||
        (key === "corrections" && identity.capabilities.includes("PUBLISH")) ||
        identity.role === "OWNER"));
  async function open(key: Tab) {
    if (!(await discard())) return;
    setDirty(false);
    setTab(key);
    writeUrl(selected, key, false, true);
  }
  const navItem = (key: Tab) =>
    available(key) && (
      <button
        key={key}
        type="button"
        className="studio-nav-item"
        aria-current={activeTab === key ? "page" : undefined}
        onClick={() => void open(key)}
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
  async function joined(workspaceId: string, created = false) {
    setReceipt(
      created
        ? "空间已创建，公开网站默认关闭。"
        : "已加入空间。你现在可以在这个空间中查看和整理获准访问的内容。",
    );
    setStarted(created);
    page.reload();
    await select(workspaceId, "content");
  }
  if (
    !page.loading &&
    !page.error &&
    page.total === 0 &&
    !selected &&
    !canCreateSpaces(account.account.group)
  )
    return (
      <Welcome
        account={account}
        onLogout={onLogout}
        onJoined={(workspaceId) => joined(workspaceId)}
      />
    );
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
      {activeTab === "connections" && (
        <div className="management-panel">
          <McpGuide />
          <Connections />
          {identity && <CaptureSetup identity={identity} />}
          {identity?.role === "OWNER" && (
            <details className="sub-panel advanced">
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
      {activeTab === "spaces" && (
        <SpacesPanel
          profile={account}
          hasWorkspace={page.total > 0}
          workspaceUnavailable={!!page.error}
          onBeforeJoin={discard}
          onJoined={async (workspaceId, created) => {
            await joined(workspaceId, created);
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
            setTab("spaces");
            writeUrl(selected, "spaces", false, true);
          }}
        />
        <nav className="studio-nav" aria-label="管理功能">
          {identity && (
            <>
              <p className="studio-nav-label">这个空间</p>
              {spaceSections.map(navItem)}
            </>
          )}
          <p className="studio-nav-label">空间</p>
          {navItem("spaces")}
          {administrator && <p className="studio-nav-label">站点</p>}
          {navItem("site")}
        </nav>
        <div className="studio-side-foot">
          <a
            className="studio-user"
            href="/account"
            title="账号与安全"
            onClick={async (event) => {
              event.preventDefault();
              if (await discard()) window.location.assign("/account");
            }}
          >
            <Avatar name={account.account.displayName} />
            <span>{account.account.displayName}</span>
          </a>
          <button
            type="button"
            className="text-button hover-underline"
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
        {started && identity?.role === "OWNER" && (
          <NextSteps
            onOpen={(key) => void open(key)}
            onDismiss={() => setStarted(false)}
          />
        )}
        {error && (
          <p className="notice danger" role="alert">
            {error}{" "}
            <button
              type="button"
              className="text-button hover-underline"
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

/** First things to do in a space the account just created; each opens its studio section. */
function NextSteps({
  onOpen,
  onDismiss,
}: {
  onOpen: (key: Tab) => void;
  onDismiss: () => void;
}) {
  const steps: { key: Tab; label: string; hint: string }[] = [
    {
      key: "content",
      label: "写第一篇笔记",
      hint: "新建文件，保存后就会记进空间。",
    },
    {
      key: "publication",
      label: "打开公开网站",
      hint: "想让别人看到时，再在这里开启网站。",
    },
    {
      key: "connections",
      label: "连接 AI 助手",
      hint: "让 Claude 等助手帮你整理和查找。",
    },
    { key: "members", label: "邀请成员", hint: "和朋友一起整理这个空间。" },
  ];
  return (
    <section className="sub-panel next-steps" aria-labelledby="next-steps">
      <div className="panel-heading">
        <h2 id="next-steps">接下来可以做什么</h2>
        <button type="button" className="text-button" onClick={onDismiss}>
          知道了
        </button>
      </div>
      <ol>
        {steps.map((step) => (
          <li key={step.key}>
            <button
              type="button"
              className="text-button hover-underline"
              onClick={() => onOpen(step.key)}
            >
              <Icon name={icons[step.key]} />
              {step.label}
            </button>
            <span className="muted">{step.hint}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}

/**
 * The studio's entrance for an account with no space that may not create one: it explains what
 * the account can do instead of showing repository tools it cannot use.
 */
function Welcome({
  account,
  onLogout,
  onJoined,
}: {
  account: AccountProfile;
  onLogout: () => Promise<void>;
  onJoined: (workspaceId: string) => Promise<void>;
}) {
  const { displayName, group } = account.account;
  return (
    <div className="page page-narrow welcome">
      <header className="page-head personal-head">
        <Avatar name={displayName} large />
        <div>
          <h1>欢迎，{displayName}</h1>
          <p>
            你的账号类型是
            <span className="group-badge">{siteGroups[group]}</span>：
            {siteGroupAbilities[group]}
          </p>
        </div>
      </header>
      <nav className="welcome-links" aria-label="从这里开始">
        <a href="/">
          <Icon name="compass" />
          <strong>发现</strong>
          <span>看看大家公开的空间和文章。</span>
        </a>
        <a href="/community">
          <Icon name="bell" />
          <strong>我的社区</strong>
          <span>关注动态、收藏和通知。</span>
        </a>
        <a href="/account">
          <Icon name="settings" />
          <strong>账号与安全</strong>
          <span>昵称、邮箱、Google 登录和密码。</span>
        </a>
      </nav>
      <JoinSpace compact onBeforeJoin={async () => true} onJoined={onJoined} />
      <section className="sub-panel" aria-labelledby="become-creator">
        <div className="panel-heading">
          <h2 id="become-creator">想拥有自己的空间？</h2>
        </div>
        <p className="muted">
          新建空间并公开成网站需要创作者资格。
          <a href="/account#creator">查看如何成为创作者</a>
        </p>
      </section>
      <p>
        <button
          type="button"
          className="text-button hover-underline"
          onClick={() => void onLogout()}
        >
          退出登录
        </button>
      </p>
    </div>
  );
}
