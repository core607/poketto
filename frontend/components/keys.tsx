"use client";
import { FormEvent, useState } from "react";
import { api } from "../lib/browser-api";
import { message, type Identity } from "./admin";
import type { Member } from "./members";
import { Secret } from "./secret";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import { useConfirmation } from "./confirmation";
const capabilities = [
  {
    key: "READ_PRIVATE",
    label: "读取私有内容",
    detail: "读取此空间中的私有笔记与图片。",
  },
  {
    key: "WRITE_PRIVATE",
    label: "修改私有内容",
    detail: "创建、编辑或删除私有文本。",
  },
  {
    key: "PUBLISH",
    label: "发布与修改公开内容",
    detail: "允许更新公开站点上的内容与引用关系。",
  },
  {
    key: "MANAGE_KEYS",
    label: "管理访问密钥",
    detail: "允许管理这个空间的其他密钥。",
  },
  {
    key: "EXECUTE_REPOSITORY",
    label: "执行仓库分析",
    detail: "允许在隔离环境中运行仓库分析命令。",
  },
];
type Key = {
  id: string;
  accountId: string;
  capabilities: string[];
  revoked: boolean;
};
export function Keys({ identity }: { identity: Identity }) {
  const confirm = useConfirmation();
  const keyPage = useAdminPage<Key>("/api/admin/keys");
  const keys = keyPage.items;
  const memberPage = useAdminPage<Member>("/api/admin/members");
  const members = memberPage.items;
  const [holder, setHolder] = useState(identity.accountId);
  const selectedHolder = members.some(
    (member) => member.active && member.accountId === holder,
  )
    ? holder
    : "";
  const [secret, setSecret] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  function refresh() {
    keyPage.reload();
    memberPage.reload();
  }
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    try {
      const value = await api<{ token: string }>("/api/admin/keys", {
        method: "POST",
        body: {
          accountId: String(data.get("accountId")),
          capabilities: data.getAll("capabilities"),
        },
      });
      setSecret(value.token);
      await refresh();
    } catch (error) {
      setError(message(error));
    } finally {
      setPending(false);
    }
  }
  async function revoke(id: string) {
    if (
      !(await confirm({
        title: "撤销访问密钥？",
        description: `撤销密钥「${id.slice(0, 8)}」后，使用它的客户端将立即失去访问权限。此操作无法撤回。`,
        confirmLabel: "确认撤销",
      }))
    )
      return;
    setPending(true);
    try {
      await api("/api/admin/keys/" + encodeURIComponent(id), {
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
          <h2>给可信的工具一把钥匙</h2>
          <p className="muted">每个客户端使用独立密钥，按需要分配权限。</p>
        </div>
      </div>
      {(error || keyPage.error || memberPage.error) && (
        <p className="notice danger" role="alert">
          {error || keyPage.error || memberPage.error}
        </p>
      )}
      {secret && (
        <Secret
          title="访问密钥只显示这一次"
          value={secret}
          onClose={() => setSecret("")}
        />
      )}
      <form className="key-form" onSubmit={create}>
        <label>
          关联成员
          <select
            name="accountId"
            required
            value={selectedHolder}
            disabled={memberPage.loading}
            onChange={(event) => setHolder(event.target.value)}
          >
            <option value="" disabled>
              请选择这一页的成员
            </option>
            {members
              .filter((member) => member.active)
              .map((member) => (
                <option key={member.accountId} value={member.accountId}>
                  {member.loginName}
                </option>
              ))}
          </select>
        </label>
        <AdminPagination
          label="关联成员"
          page={memberPage}
          disabled={pending}
        />
        <fieldset>
          <legend>允许的能力</legend>
          <div className="capabilities">
            {capabilities.map((capability, index) => (
              <label className="capability" key={capability.key}>
                <input
                  type="checkbox"
                  name="capabilities"
                  value={capability.key}
                  defaultChecked={index < 2}
                />
                <span>
                  <strong>{capability.label}</strong>
                  <small>{capability.detail}</small>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
        <button disabled={pending || memberPage.loading || !selectedHolder}>
          创建密钥 ↗
        </button>
      </form>
      <section className="sub-panel">
        <h2>已创建的密钥</h2>
        <div className="key-list">
          {keys.map((key) => (
            <article className="key-item" key={key.id}>
              <div>
                <h3>
                  {members.find((member) => member.accountId === key.accountId)
                    ?.loginName ?? key.accountId}
                  <span className="muted"> · {key.id.slice(0, 8)}</span>
                </h3>
                <p>
                  {key.capabilities
                    .map(
                      (item) =>
                        capabilities.find(
                          (capability) => capability.key === item,
                        )?.label ?? item,
                    )
                    .join("、") || "无已分配能力"}
                </p>
              </div>
              {key.revoked ? (
                <span className="muted">已撤销</span>
              ) : (
                <button
                  className="text-button danger-text"
                  disabled={pending}
                  onClick={() => void revoke(key.id)}
                >
                  撤销
                </button>
              )}
            </article>
          ))}
        </div>
        <AdminPagination label="密钥" page={keyPage} disabled={pending} />
        {!keys.length && <p className="muted">还没有访问密钥。</p>}
      </section>
    </div>
  );
}
