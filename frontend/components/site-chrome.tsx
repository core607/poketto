"use client";
import { useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation";
import { api, ApiError } from "../lib/browser-api";
import { spaceHref } from "../lib/format";
import { clearAccountDrafts, withDraftStorage } from "../lib/local-drafts";
import type { AccountProfile } from "./account-panel";
import { Avatar, BrandMark, Icon } from "./ui/icons";
import { ThemeSwitch } from "./theme-switch";

export function SiteBar() {
  const pathname = usePathname();
  const discovering = pathname === "/" || pathname.startsWith("/s/");
  return (
    <header className="site-bar">
      <div className="site-bar-inner">
        <a href="/" className="brand" aria-label="Poketto 首页">
          <BrandMark />
          Poketto
        </a>
        <nav className="site-nav" aria-label="主导航">
          <a href="/" aria-current={discovering ? "page" : undefined}>
            <Icon name="compass" />
            <span className="nav-label">发现</span>
          </a>
          <a
            href="/search"
            aria-current={pathname === "/search" ? "page" : undefined}
          >
            <Icon name="search" />
            <span className="nav-label">搜索</span>
          </a>
        </nav>
        <AccountEntrance />
      </div>
    </header>
  );
}

type Session =
  | { state: "checking" }
  | { state: "anonymous" }
  | { state: "signed-in"; profile: AccountProfile };

function AccountEntrance() {
  const [session, setSession] = useState<Session>({ state: "checking" });
  const [failed, setFailed] = useState(false);
  const menu = useRef<HTMLDetailsElement>(null);
  useEffect(() => {
    let active = true;
    const load = () =>
      void api<AccountProfile>("/api/auth/account")
        .then((profile) => {
          if (!active) return;
          setSession({ state: "signed-in", profile });
          setFailed(false);
        })
        .catch((error) => {
          if (!active) return;
          setSession({ state: "anonymous" });
          setFailed(!(error instanceof ApiError && error.status === 401));
        });
    load();
    // Sign-in dialogs elsewhere on the page announce a new session.
    window.addEventListener("poketto:session", load);
    return () => {
      active = false;
      window.removeEventListener("poketto:session", load);
    };
  }, []);
  useEffect(() => {
    const close = (event: MouseEvent) => {
      if (menu.current?.open && !menu.current.contains(event.target as Node))
        menu.current.open = false;
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && menu.current?.open) {
        menu.current.open = false;
        menu.current.querySelector("summary")?.focus();
      }
    };
    document.addEventListener("click", close);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("click", close);
      document.removeEventListener("keydown", escape);
    };
  }, []);
  async function logout(accountId: string) {
    try {
      await api("/api/auth/logout", { method: "POST" });
    } catch {
      return;
    }
    try {
      await withDraftStorage((storage) =>
        clearAccountDrafts(storage, accountId),
      );
    } catch {
      // The session has ended; drafts left behind remain the browser's to clear.
    }
    window.location.assign("/");
  }
  if (session.state === "checking")
    return <div className="bar-actions" aria-hidden />;
  if (session.state === "anonymous")
    return (
      <div className="bar-actions">
        {failed && (
          <span className="muted hide-narrow" role="status">
            账号状态暂时不可用
          </span>
        )}
        <a className="btn btn-primary" href="/admin">
          登录
        </a>
      </div>
    );
  const { account } = session.profile;
  const name = account.displayName || account.loginName;
  return (
    <div className="bar-actions">
      <a className="btn btn-secondary hide-narrow" href="/admin">
        <Icon name="pen" />
        工作台
      </a>
      <details className="menu" ref={menu}>
        <summary className="account-trigger" aria-label="账号菜单">
          <Avatar name={name} />
          <span className="account-name">{name}</span>
        </summary>
        <div className="menu-panel">
          <div className="menu-head">
            <strong>{name}</strong>
            <span>{account.loginName}</span>
          </div>
          <a href="/admin">
            <Icon name="pen" />
            工作台
          </a>
          <a href="/community?tab=notifications">
            <Icon name="bell" />
            通知
          </a>
          <a href="/community?tab=bookmarks">
            <Icon name="bookmark" />
            收藏
          </a>
          <a href="/community?tab=following">
            <Icon name="users" />
            关注的空间
          </a>
          <hr />
          <a href="/admin?tab=account">
            <Icon name="settings" />
            账号与安全
          </a>
          <div className="theme-row">
            外观
            <ThemeSwitch compact />
          </div>
          {account.siteAdministrator && (
            <a href="/admin?tab=site">
              <Icon name="shield" />
              站务
            </a>
          )}
          <hr />
          <button onClick={() => void logout(account.accountId)}>
            <Icon name="logout" />
            退出登录
          </button>
        </div>
      </details>
    </div>
  );
}

export function SiteFooter() {
  const pathname = usePathname();
  // Inside a space site the feed link follows that space.
  const space = /^\/s\/([^/]+)/.exec(pathname)?.[1];
  return (
    <footer className="site-footer">
      <div className="site-footer-inner">
        <span className="footer-brand">
          <BrandMark />
          Poketto · 给想法一个留下来的地方
        </span>
        <ThemeSwitch />
        <nav aria-label="站点信息">
          <a href={spaceHref(space) + "/rss.xml"}>RSS</a>
          <a href="/privacy">隐私政策</a>
          <a href="/terms">服务条款</a>
        </nav>
      </div>
    </footer>
  );
}
