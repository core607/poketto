"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api, ApiError } from "../lib/browser-api";
import { githubFailures, githubRoot } from "../lib/github-spaces";
import { message } from "./admin";

type Status = { authorizingAccount: boolean; revoked: boolean };

export function GitHubRepositoryReconnection({
  workspaceId,
  repository,
  onReconnected,
}: {
  workspaceId: string;
  repository: string;
  onReconnected: () => Promise<void>;
}) {
  const base = `${githubRoot}/repositories/${encodeURIComponent(workspaceId)}`;
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [receipt, setReceipt] = useState("");
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    let active = true;
    setStatus(null);
    setError("");
    void api<Status>(base).then(
      (result) => {
        if (active) setStatus(result);
      },
      (failure) => {
        if (active) setError(message(failure));
      },
    );
    return () => {
      active = false;
    };
  }, [base, retry]);

  async function reconnect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const values = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    setReceipt("");
    try {
      await api(base + "/reconnect", {
        method: "POST",
        body: { repositoryName: String(values.get("repositoryName") ?? "") },
        timeoutMs: 90000,
      });
      setStatus({ authorizingAccount: true, revoked: false });
      setReceipt("已核对原仓库并恢复连接。");
      await onReconnected();
    } catch (failure) {
      setError(
        (failure instanceof ApiError &&
          failure.code &&
          githubFailures[failure.code]) ||
          message(failure),
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <div aria-label="GitHub 仓库授权">
      <p>这个仓库通过 GitHub App 授权连接。</p>
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {receipt && (
        <p className="notice" role="status">
          {receipt}
        </p>
      )}
      {!status && !error && <p role="status">正在读取 GitHub 连接…</p>}
      {!status && error && (
        <button onClick={() => setRetry(retry + 1)}>
          重新读取 GitHub 连接
        </button>
      )}
      {status && !status.authorizingAccount && (
        <p>请由最初授权此仓库的空间主人恢复 GitHub 连接。</p>
      )}
      {status?.authorizingAccount && (
        <form onSubmit={reconnect}>
          {status.revoked && (
            <p>GitHub 已撤销仓库访问，恢复授权后需要重新连接。</p>
          )}
          <p>
            先到<a href="/admin?tab=account">账号设置</a>恢复原 GitHub
            账号授权，并在仓库授权范围中选中这个仓库。
          </p>
          <p>
            重连只接受原仓库。仓库改名后可填写新名称；转移给其他账号或重新创建的同名仓库不能接替。
          </p>
          <fieldset disabled={pending}>
            <label>
              当前仓库名称
              <input
                name="repositoryName"
                required
                maxLength={100}
                pattern="[A-Za-z0-9_.\-]+"
                defaultValue={repository.slice(repository.lastIndexOf("/") + 1)}
              />
            </label>
            <button type="submit">
              {pending ? "正在核对仓库…" : "核对并恢复连接"}
            </button>
          </fieldset>
        </form>
      )}
    </div>
  );
}
