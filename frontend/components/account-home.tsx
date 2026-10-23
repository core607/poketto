"use client";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError, message } from "../lib/browser-api";
import {
  canCreateSpaces,
  siteGroupAbilities,
  siteGroups,
  type SiteGroup,
} from "../lib/site-groups";
import type { AccountProfile } from "../lib/types";
import { AccountSecurity } from "./account-security";
import { CreatorApplication } from "./creator-application";
import { Login } from "./login";
import { Avatar, Icon } from "./ui/icons";

/** The signed-in account's own page: who it is, what its group allows, and login security. */
export function AccountHome() {
  const [profile, setProfile] = useState<AccountProfile | null>(null);
  const [state, setState] = useState<"loading" | "anonymous" | "ready">(
    "loading",
  );
  const [displayName, setDisplayName] = useState("");
  const [spaces, setSpaces] = useState(0);
  const [error, setError] = useState("");
  const groupChanged = useCallback(
    (group: SiteGroup) => {
      if (profile?.account.group === group) return;
      setProfile(
        (current) =>
          current && {
            account: {
              ...current.account,
              group,
              siteAdministrator: group === "ADMINISTRATOR",
            },
          },
      );
      window.dispatchEvent(new Event("poketto:session"));
    },
    [profile?.account.group],
  );
  async function load() {
    setError("");
    try {
      const value = await api<AccountProfile>("/api/auth/account");
      setProfile(value);
      setDisplayName(value.account.displayName);
      setState("ready");
      if (!canCreateSpaces(value.account.group))
        void api<{ total: number }>("/api/auth/workspaces?offset=0&limit=1")
          .then((page) => setSpaces(page.total))
          .catch(() => setSpaces(0));
    } catch (failure) {
      setProfile(null);
      setState("anonymous");
      if (!(failure instanceof ApiError && failure.status === 401))
        setError(message(failure));
    }
  }
  useEffect(() => {
    void load();
  }, []);
  if (state === "loading")
    return (
      <p role="status" className="page muted">
        正在确认会话…
      </p>
    );
  if (!profile)
    return (
      <div className="page">
        <Login onLogin={load} />
        {error && (
          <p role="alert" className="notice danger">
            {error}
          </p>
        )}
      </div>
    );
  const { group } = profile.account;
  const creator = canCreateSpaces(group);
  return (
    <div className="page page-narrow account-home">
      <section className="profile-card" aria-label="账号">
        <Avatar name={displayName} large />
        <div>
          <h1>{displayName}</h1>
          <p className="muted">
            {profile.account.loginName} ·{" "}
            <span className="group-badge">{siteGroups[group]}</span>
          </p>
        </div>
      </section>
      <nav className="account-links" aria-label="我的去处">
        <a href="/community">
          <Icon name="bell" />
          我的社区
        </a>
        {(creator || spaces > 0) && (
          <a href="/admin">
            <Icon name="pen" />
            工作台
          </a>
        )}
        {profile.account.siteAdministrator && (
          <a href="/site">
            <Icon name="shield" />
            站务
          </a>
        )}
      </nav>
      <section className="sub-panel" id="creator" aria-labelledby="group">
        <div className="panel-heading">
          <h2 id="group">账号类型：{siteGroups[group]}</h2>
        </div>
        <p>{siteGroupAbilities[group]}</p>
        {!creator && (
          <>
            <p className="muted">
              想新建自己的空间并公开成网站，需要站点管理员授予创作者资格。已加入的空间不受账号类型影响。
            </p>
          </>
        )}
        <CreatorApplication onGroup={groupChanged} />
      </section>
      <AccountSecurity onDisplayName={setDisplayName} />
    </div>
  );
}
