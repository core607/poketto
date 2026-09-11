"use client";
import { FormEvent, useState } from "react";
import { api } from "../lib/browser-api";
import { date } from "../lib/format";
import { message } from "./admin";
import { Secret } from "./secret";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import { useConfirmation } from "./confirmation";

export type AccountProfile = {
  account: { accountId: string; loginName: string; siteAdministrator: boolean };
  mayIssueRegistrationInvitations: boolean;
};
type Invitation = {
  id: string;
  expiresAt: string;
  revoked: boolean;
  used: boolean;
};

export function AccountPanel({
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
  onJoined: (workspaceId: string) => Promise<void>;
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
      const joined = await api<{ workspaceId: string }>("/api/auth/invitations/accept", {
        method: "POST",
        body: { token },
      });
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
      <RegistrationInvitations
        mayIssue={profile.mayIssueRegistrationInvitations}
      />
    </div>
  );
}

export function RegistrationInvitations({ mayIssue }: { mayIssue: boolean }) {
  const page = useAdminPage<Invitation>("/api/auth/registration-invitations");
  const confirm = useConfirmation();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [secret, setSecret] = useState("");
  const [shareLink, setShareLink] = useState(false);
  async function issue() {
    setPending(true);
    setError("");
    setSecret("");
    setShareLink(false);
    try {
      const result = await api<{ token: string }>(
        "/api/auth/registration-invitations",
        { method: "POST" },
      );
      setSecret(result.token);
      page.reload();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  async function revoke(id: string) {
    setPending(true);
    setError("");
    try {
      if (
        !(await confirm({
          title: "撤销这个注册邀请码？",
          description: "尚未注册的人将无法继续使用它。已经创建的账号不受影响。",
          confirmLabel: "撤销邀请码",
        }))
      )
        return;
      await api(
        "/api/auth/registration-invitations/" + encodeURIComponent(id),
        { method: "DELETE" },
      );
      page.reload();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  if (!mayIssue && !page.loading && !page.error && page.total === 0)
    return null;
  return (
    <section className="sub-panel">
      <div className="panel-heading">
        <div>
          <h2>邀请注册</h2>
          <p className="muted">
            邀请码 7 天内有效，只能注册一个账号，不会让对方加入你的空间。
          </p>
        </div>
        {mayIssue && (
          <button onClick={issue} disabled={pending}>
            创建注册邀请码
          </button>
        )}
      </div>
      {!mayIssue && (
        <p className="muted">
          当前账号没有发放注册邀请码的权限。你仍可查看和撤销自己此前发出的邀请。
        </p>
      )}
      {(error || page.error) && (
        <p role="alert" className="notice danger">
          {error || page.error}
        </p>
      )}
      {secret && (
        <div>
          <button
            className="text-button"
            onClick={() => setShareLink(!shareLink)}
          >
            {shareLink ? "改为邀请码" : "改为注册链接"}
          </button>
          <Secret
            key={secret + String(shareLink)}
            title={
              shareLink ? "注册链接只显示这一次" : "注册邀请码只显示这一次"
            }
            value={
              shareLink
                ? window.location.origin +
                  "/admin#register=" +
                  encodeURIComponent(secret)
                : secret
            }
            onClose={() => setSecret("")}
          />
        </div>
      )}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>到期时间</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {page.items.map((invitation) => {
              const expired = Date.parse(invitation.expiresAt) <= Date.now();
              const active =
                !invitation.used && !invitation.revoked && !expired;
              return (
                <tr key={invitation.id}>
                  <td>{date(invitation.expiresAt)}</td>
                  <td>
                    {invitation.used
                      ? "已使用"
                      : invitation.revoked
                        ? "已撤销"
                        : expired
                          ? "已过期"
                          : "待使用"}
                  </td>
                  <td>
                    {active && (
                      <button
                        className="text-button"
                        disabled={pending}
                        onClick={() => void revoke(invitation.id)}
                      >
                        撤销
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
            {!page.loading && !page.error && page.total === 0 && (
              <tr>
                <td colSpan={3}>还没有发出注册邀请。</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <AdminPagination label="注册邀请" page={page} disabled={pending} />
    </section>
  );
}
