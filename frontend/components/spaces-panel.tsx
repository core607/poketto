"use client";
import { FormEvent, useState } from "react";
import { api, message } from "../lib/browser-api";
import { canCreateSpaces } from "../lib/site-groups";
import type { AccountProfile } from "../lib/types";
import { CreateWorkspace } from "./create-workspace";
import { GitHubSpace } from "./github-space";

/** Accepts a workspace invitation code; joining never changes the account's site group. */
export function JoinSpace({
  onBeforeJoin,
  onJoined,
  compact = false,
}: {
  onBeforeJoin: () => Promise<boolean>;
  onJoined: (workspaceId: string) => Promise<void>;
  compact?: boolean;
}) {
  const [pending, setPending] = useState(false);
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
        { method: "POST", body: { token } },
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
    <section className="sub-panel" aria-labelledby="join-space">
      <div className="panel-heading">
        <h2 id="join-space">{compact ? "有邀请码？" : "加入空间"}</h2>
      </div>
      <p className="muted">
        输入空间主人提供的邀请码，就能查看和整理获准访问的内容。加入空间不会改变你的账号类型。
      </p>
      <form onSubmit={join} className="inline-form">
        <label>
          空间邀请码
          <input name="token" required maxLength={256} autoComplete="off" />
        </label>
        <button disabled={pending}>{pending ? "正在加入…" : "加入空间"}</button>
      </form>
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </section>
  );
}

/** Creating or joining spaces. Repository-level options appear only to accounts that may create. */
export function SpacesPanel({
  profile,
  hasWorkspace,
  workspaceUnavailable = false,
  onBeforeJoin,
  onJoined,
}: {
  profile: AccountProfile;
  hasWorkspace: boolean;
  workspaceUnavailable?: boolean;
  onBeforeJoin: () => Promise<boolean>;
  onJoined: (workspaceId: string, created?: boolean) => Promise<void>;
}) {
  const creator = canCreateSpaces(profile.account.group);
  return (
    <div className="management-panel">
      <div className="panel-intro">
        <p className="muted">
          {!hasWorkspace && !workspaceUnavailable
            ? creator
              ? "你还没有空间。新建一个，或者用邀请码加入别人的空间。"
              : "你还没有空间。可以用邀请码加入别人的空间。"
            : creator
              ? "新建一个空间，或者用邀请码加入别人的空间。"
              : "用邀请码加入别人的空间。"}
        </p>
      </div>
      <GitHubSpace
        key={profile.account.accountId}
        accountId={profile.account.accountId}
        onBeforeLeave={onBeforeJoin}
        onCreated={(workspaceId) => onJoined(workspaceId, true)}
      />
      <JoinSpace
        onBeforeJoin={onBeforeJoin}
        onJoined={(workspaceId) => onJoined(workspaceId)}
      />
      {creator && (
        <details className="sub-panel advanced">
          <summary>手动连接已有仓库（高级，需要了解 Git）</summary>
          <CreateWorkspace
            accountId={profile.account.accountId}
            onCreated={(workspaceId) => onJoined(workspaceId, true)}
          />
        </details>
      )}
    </div>
  );
}
