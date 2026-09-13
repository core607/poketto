"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError } from "../lib/browser-api";
import { message } from "./admin";

type ConnectionInfo = {
  managed: boolean;
  rotationAvailable: boolean;
  binding: { repository: string; updatedAt: string } | null;
};

const rotationErrors: Record<string, string> = {
  PERMISSION_DENIED:
    "新令牌未通过权限验证。请确认它能读取仓库信息，并拥有 Git 读写权限。",
  PRIVATE_REPOSITORY_REQUIRED:
    "已连接的仓库必须保持私有，请检查 Git 托管平台的仓库设置。",
  REPOSITORY_CHANGED: "仓库连接或凭据已发生变化，请重新读取连接信息后再更新。",
  INVALID_INPUT: "请检查 Git 用户名和新令牌是否填写完整。",
};

export function RepositoryConnection({ workspaceId }: { workspaceId: string }) {
  const base = `/api/auth/workspaces/${encodeURIComponent(workspaceId)}`;
  const [connection, setConnection] = useState<ConnectionInfo | null>(null);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const [pending, setPending] = useState(false);
  const active = useRef(false);

  async function load() {
    try {
      const result = await api<ConnectionInfo>(base + "/repository-connection");
      if (active.current) {
        setConnection(result);
        setError("");
      }
    } catch (failure) {
      if (active.current) setError(message(failure));
    }
  }
  useEffect(() => {
    active.current = true;
    void load();
    return () => {
      active.current = false;
    };
  }, []);

  async function rotate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const form = event.currentTarget;
    const values = new FormData(form);
    const username = String(values.get("username") ?? "");
    const token = String(values.get("token") ?? "");
    setPending(true);
    setReceipt("");
    setError("");
    form.reset();
    try {
      await api(base + "/repository-credentials", {
        method: "PUT",
        body: { username, token },
        timeoutMs: 90000,
      });
      if (active.current) {
        setReceipt("仓库凭据已更新。之后的仓库访问使用新令牌。");
        await load();
      }
    } catch (failure) {
      if (active.current)
        setError(
          (failure instanceof ApiError &&
            failure.code &&
            rotationErrors[failure.code]) ||
            message(failure),
        );
    } finally {
      if (active.current) setPending(false);
    }
  }

  return (
    <section className="sub-panel" aria-label="仓库连接">
      <h2>仓库连接</h2>
      <p className="muted">这个空间的内容保存在已连接的 Git 仓库中。</p>
      {receipt && (
        <p className="notice" role="status">
          {receipt}
        </p>
      )}
      {error && (
        <p className="notice danger" role="alert">
          {error}{" "}
          <button disabled={pending} onClick={() => void load()}>
            重新读取连接信息
          </button>
        </p>
      )}
      {!connection && !error && <p role="status">正在读取仓库连接…</p>}
      {connection && !connection.managed && (
        <p>这个空间的仓库由部署配置管理。请联系站点管理员更新凭据。</p>
      )}
      {connection?.managed && connection.binding && (
        <>
          <p>
            已连接仓库：<span>{connection.binding.repository}</span>
          </p>
          <p className="muted">
            凭据更新时间：
            {new Date(connection.binding.updatedAt).toLocaleString("zh-CN")}
          </p>
          {!connection.rotationAvailable ? (
            <p>站点尚未配置凭据加密密钥，请联系站点管理员恢复配置后再更新。</p>
          ) : (
            <form onSubmit={rotate} autoComplete="off">
              <p>
                新令牌需要仓库信息读取和 Git
                读写权限。验证通过后才会替换现有凭据，仓库地址保持不变。
              </p>
              <fieldset disabled={pending}>
                <label>
                  Git 用户名
                  <input
                    name="username"
                    required
                    maxLength={256}
                    autoComplete="off"
                  />
                </label>
                <label>
                  新的仓库令牌
                  <input
                    name="token"
                    type="password"
                    required
                    maxLength={4096}
                    autoComplete="new-password"
                  />
                </label>
                <button type="submit">
                  {pending ? "正在验证并更新…" : "验证并更新凭据"}
                </button>
              </fieldset>
              <p className="muted">
                令牌提交后会清空，也不会保存在浏览器存储中。更新成功后，再到 Git
                托管平台撤销旧令牌。
              </p>
            </form>
          )}
        </>
      )}
    </section>
  );
}
