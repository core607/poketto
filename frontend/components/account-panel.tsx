"use client";
import { FormEvent, useEffect, useState } from "react";
import { api } from "../lib/browser-api";
import { message } from "./admin";
import { AccountSecurity } from "./account-security";
import { CreateWorkspace } from "./create-workspace";
import { GitHubSpace } from "./github-space";
import { SiteAccounts, SiteGroup, siteGroups } from "./site-accounts";

export type AccountProfile = {
  account: {
    accountId: string;
    loginName: string;
    displayName: string;
    siteAdministrator: boolean;
    group: SiteGroup;
  };
};

export function AccountPanel({
  profile,
  hasWorkspace,
  workspaceUnavailable = false,
  onBeforeJoin,
  onJoined,
  onDisplayName,
}: {
  profile: AccountProfile;
  hasWorkspace: boolean;
  workspaceUnavailable?: boolean;
  onBeforeJoin: () => Promise<boolean>;
  onJoined: (workspaceId: string, created?: boolean) => Promise<void>;
  onDisplayName?: (name: string) => void;
}) {
  const [pending, setPending] = useState(false);
  const [security, setSecurity] = useState(false);
  useEffect(() => {
    const url = new URL(window.location.href);
    if (url.searchParams.get("security") !== "1") return;
    setSecurity(true);
    url.searchParams.delete("security");
    window.history.replaceState(
      window.history.state,
      "",
      url.pathname + url.search + url.hash,
    );
  }, []);
  const [error, setError] = useState("");
  async function join(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const token = String(new FormData(form).get("token"));
    setPending(true);
    setError("");
    try {
      if (!(await onBeforeJoin())) return;
      const joined = await api<{ workspaceId: string }>(
        "/api/auth/invitations/accept",
        {
          method: "POST",
          body: { token },
        },
      );
      form.reset();
      await onJoined(joined.workspaceId);
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="management-panel">
      <p>当前策略组：{siteGroups[profile.account.group]}</p>
      <button className="text-button" onClick={() => setSecurity(!security)}>
        {security ? "收起登录与安全" : "登录与安全"}
      </button>
      {security && <AccountSecurity onDisplayName={onDisplayName} />}
      {profile.account.siteAdministrator && <SiteAccounts />}
      <GitHubSpace
        key={profile.account.accountId}
        accountId={profile.account.accountId}
        onBeforeLeave={onBeforeJoin}
        onCreated={(workspaceId) => onJoined(workspaceId, true)}
      />
      <details>
        <summary>手动连接已有仓库（高级）</summary>
        <CreateWorkspace
          accountId={profile.account.accountId}
          onCreated={(workspaceId) => onJoined(workspaceId, true)}
        />
      </details>
      <section>
        <div className="panel-heading">
          <h2>加入空间</h2>
        </div>
        {!hasWorkspace && !workspaceUnavailable && (
          <p>你已登录，还没有可访问的空间。</p>
        )}
        <p className="muted">
          输入空间主人提供的邀请码。加入空间不会授予站点管理权限。
        </p>
        <form onSubmit={join}>
          <label>
            空间邀请码
            <input name="token" required maxLength={256} autoComplete="off" />
          </label>
          <button disabled={pending}>
            {pending ? "正在加入…" : "加入空间"}
          </button>
        </form>
        {error && (
          <p role="alert" className="notice danger">
            {error}
          </p>
        )}
      </section>
    </div>
  );
}
