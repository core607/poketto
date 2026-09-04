"use client";
import { FormEvent, useEffect, useState } from "react";
import { api } from "../lib/browser-api";
import { date } from "../lib/format";
import { message } from "./admin";
import { Secret } from "./secret";
export type Member = {
  accountId: string;
  loginName: string;
  role: "OWNER" | "MEMBER";
  active: boolean;
};
type Invitation = {
  id: string;
  expiresAt: string;
  revoked: boolean;
  used: boolean;
};
export function Members() {
  const [members, setMembers] = useState<Member[]>([]);
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [secret, setSecret] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  async function refresh() {
    const [users, invites] = await Promise.all([
      api<Member[]>("/api/admin/members"),
      api<Invitation[]>("/api/admin/invitations"),
    ]);
    setMembers(users);
    setInvitations(invites);
  }
  useEffect(() => {
    void refresh().catch((error) => setError(message(error)));
  }, []);
  async function update(member: Member, values: Partial<Member>) {
    setPending(true);
    setError("");
    try {
      await api("/api/admin/members/" + encodeURIComponent(member.accountId), {
        method: "PUT",
        body: {
          role: values.role ?? member.role,
          active: values.active ?? member.active,
        },
      });
      await refresh();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  async function invite() {
    setPending(true);
    setError("");
    try {
      const result = await api<{ token: string }>("/api/admin/invitations", {
        method: "POST",
      });
      setSecret(result.token);
      await refresh();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  async function revoke(id: string) {
    setPending(true);
    try {
      await api("/api/admin/invitations/" + encodeURIComponent(id), {
        method: "DELETE",
      });
      await refresh();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  async function accept(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    setPending(true);
    try {
      await api("/api/auth/invitations/accept", {
        method: "POST",
        body: { token: String(new FormData(form).get("token")) },
      });
      form.reset();
      await refresh();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <div className="management-panel">
      <div className="panel-heading">
        <div>
          <h2>一起整理的人</h2>
          <p className="muted">管理这个空间的成员和一次性邀请。</p>
        </div>
        <button onClick={invite} disabled={pending}>
          创建邀请 ↗
        </button>
      </div>
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {secret && (
        <Secret
          title="邀请凭证只显示这一次"
          value={secret}
          onClose={() => setSecret("")}
        />
      )}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>成员</th>
              <th>角色</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {members.map((member) => (
              <tr key={member.accountId}>
                <td>{member.loginName}</td>
                <td>
                  <select
                    aria-label={member.loginName + "的角色"}
                    value={member.role}
                    disabled={pending}
                    onChange={(event) =>
                      void update(member, {
                        role: event.target.value as Member["role"],
                      })
                    }
                  >
                    <option value="OWNER">所有者</option>
                    <option value="MEMBER">成员</option>
                  </select>
                </td>
                <td>{member.active ? "正常" : "已停用"}</td>
                <td>
                  <button
                    className="text-button"
                    disabled={pending}
                    onClick={() => {
                      if (
                        member.active &&
                        !window.confirm(
                          "停用后，这位成员和关联密钥将无法访问空间。确认停用？",
                        )
                      )
                        return;
                      void update(member, { active: !member.active });
                    }}
                  >
                    {member.active ? "停用" : "重新启用"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <section className="sub-panel">
        <h2>邀请记录</h2>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>有效期至</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {invitations.map((invite) => (
                <tr key={invite.id}>
                  <td>{date(invite.expiresAt)}</td>
                  <td>
                    {invite.used
                      ? "已使用"
                      : invite.revoked
                        ? "已撤销"
                        : new Date(invite.expiresAt) <= new Date()
                          ? "已过期"
                          : "待接受"}
                  </td>
                  <td>
                    {!invite.used && !invite.revoked && (
                      <button
                        className="text-button"
                        disabled={pending}
                        onClick={() => void revoke(invite.id)}
                      >
                        撤销
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!invitations.length && <p className="muted">还没有创建邀请。</p>}
      </section>
      <details className="sub-panel">
        <summary>已有账号，接受邀请</summary>
        <form onSubmit={accept} className="inline-form">
          <label>
            邀请凭证
            <input name="token" type="password" required autoComplete="off" />
          </label>
          <button disabled={pending}>接受邀请</button>
        </form>
      </details>
    </div>
  );
}
