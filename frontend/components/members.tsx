"use client";
import { useState } from "react";
import { useWorkspaceApi } from "./workspace-context";
import { date } from "../lib/format";
import { message } from "./admin";
import { Secret } from "./secret";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import { useConfirmation } from "./confirmation";
import { PermissionFields, permissionSummary } from "./content-permissions";
export type Member = {
  accountId: string;
  loginName: string;
  role: "OWNER" | "MEMBER";
  active: boolean;
  permissions: string[];
};
type Invitation = {
  id: string;
  expiresAt: string;
  revoked: boolean;
  used: boolean;
  permissions: string[];
};
export function Members() {
  const api = useWorkspaceApi();
  const confirm = useConfirmation();
  const memberPage = useAdminPage<Member>("/api/admin/members");
  const members = memberPage.items;
  const invitationPage = useAdminPage<Invitation>("/api/admin/invitations");
  const invitations = invitationPage.items;
  const [secret, setSecret] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [invitePermissions, setInvitePermissions] = useState<string[]>([]);
  const [editing, setEditing] = useState<Member | null>(null);
  function refresh() {
    memberPage.reload();
    invitationPage.reload();
  }
  async function update(member: Member, values: Partial<Member>) {
    setPending(true);
    setError("");
    try {
      await api("/api/admin/members/" + encodeURIComponent(member.accountId), {
        method: "PUT",
        body: {
          role: values.role ?? member.role,
          active: values.active ?? member.active,
          permissions: values.permissions ?? member.permissions,
        },
      });
      await refresh();
      setEditing(null);
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
        body: { permissions: invitePermissions },
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
  return (
    <div className="management-panel">
      <div className="panel-heading">
        <div>
          <h2>一起整理的人</h2>
          <p className="muted">管理这个空间的成员和一次性邀请。</p>
        </div>
      </div>
      <section className="sub-panel">
        <PermissionFields label="新成员的权限" value={invitePermissions} onChange={setInvitePermissions} disabled={pending} />
        <p className="muted">默认只能查看空间里的公开目录。私密读取、私密修改和公开发布需要单独授权。</p>
        <button onClick={invite} disabled={pending}>创建空间邀请码</button>
      </section>
      {(error || memberPage.error || invitationPage.error) && (
        <p className="notice danger" role="alert">
          {error || memberPage.error || invitationPage.error}
        </p>
      )}
      {secret && (
        <Secret
          title="空间邀请码只显示这一次"
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
              <th>内容权限</th>
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
                <td>{member.role === "OWNER" ? "全部权限" : permissionSummary(member.permissions)}</td>
                <td>
                  {member.role === "MEMBER" && (
                    <button className="text-button" disabled={pending} onClick={() => setEditing(member)}>
                      设置权限
                    </button>
                  )}
                  <button
                    className="text-button"
                    disabled={pending}
                    onClick={async () => {
                      if (
                        member.active &&
                        !(await confirm({
                          title: "停用成员？",
                          description: `停用「${member.loginName}」后，这位成员和关联密钥将无法访问空间。`,
                          confirmLabel: "确认停用",
                        }))
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
      <AdminPagination label="成员" page={memberPage} disabled={pending} />
      {editing && (
        <section className="sub-panel" aria-label={editing.loginName + "的权限"}>
          <PermissionFields label={editing.loginName + "的内容权限"} value={editing.permissions}
            onChange={(permissions) => setEditing({ ...editing, permissions })} disabled={pending} />
          <p className="muted">收回权限会断开超出权限的 AI 连接和密钥。增加权限后，已有连接仍保持原来的授权。</p>
          <button disabled={pending} onClick={() => void update(editing, { permissions: editing.permissions })}>保存权限</button>
          <button className="button-secondary" disabled={pending} onClick={() => setEditing(null)}>取消</button>
        </section>
      )}
      <section className="sub-panel">
        <h2>邀请记录</h2>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>有效期至</th>
                <th>状态</th>
                <th>初始权限</th>
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
                  <td>{permissionSummary(invite.permissions)}</td>
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
        <AdminPagination
          label="邀请"
          page={invitationPage}
          disabled={pending}
        />
        {!invitations.length && <p className="muted">还没有创建邀请。</p>}
      </section>
    </div>
  );
}
