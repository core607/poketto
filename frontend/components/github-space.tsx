"use client";
import { type FormEvent, useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import {
  githubFailures,
  githubRoot,
  githubStages,
  restoreGitHubRequest,
  type GitHubEntry,
  type GitHubHistory,
  type GitHubRequest,
  type GitHubResult,
  type GitHubStatus,
} from "../lib/github-spaces";
import { message } from "./admin";

export function GitHubSpace({
  accountId,
  onBeforeLeave,
  onCreated,
}: {
  accountId: string;
  onBeforeLeave: () => Promise<boolean>;
  onCreated: (workspace: string) => Promise<void>;
}) {
  const storageKey = "poketto.github-creation." + accountId;
  const [connection, setConnection] = useState<GitHubStatus | null>(null);
  const [draft, setDraft] = useState<GitHubRequest | null>(null);
  const [result, setResult] = useState<GitHubResult | null>(null);
  const [history, setHistory] = useState<GitHubHistory>({
    items: [],
    nextOffset: null,
  });
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [installation, setInstallation] = useState("");
  const [disconnecting, setDisconnecting] = useState(false);

  function failure(value: unknown) {
    return value instanceof ApiError && value.code && githubFailures[value.code]
      ? githubFailures[value.code]
      : message(value);
  }
  function remember(value: GitHubRequest | null) {
    setDraft(value);
    try {
      if (value) sessionStorage.setItem(storageKey, JSON.stringify(value));
      else sessionStorage.removeItem(storageKey);
    } catch {
      /* Server history retains submitted requests. */
    }
  }
  useEffect(() => {
    let active = true;
    let stored: GitHubRequest | null = null;
    try {
      stored = restoreGitHubRequest(sessionStorage.getItem(storageKey));
    } catch {
      /* Storage is optional. */
    }
    setDraft(stored);
    const url = new URL(window.location.href);
    const returned = url.searchParams.get("github");
    if (returned) {
      setNotice(
        returned === "connected"
          ? "GitHub 已授权。确认仓库信息后才会创建空间。"
          : returned === "cancelled"
            ? "GitHub 授权已取消。"
            : "GitHub 授权未完成，请重试。",
      );
      url.searchParams.delete("github");
      window.history.replaceState(
        window.history.state,
        "",
        url.pathname + url.search + url.hash,
      );
    }
    void Promise.all([
      api<GitHubStatus>(githubRoot),
      api<GitHubHistory>(githubRoot + "/creations"),
    ])
      .then(([status, records]) => {
        if (!active) return;
        setConnection(status);
        setHistory(records);
        const current = records.items.find(
          (entry) => entry.request.requestId === stored?.requestId,
        );
        if (current) setResult(current.result);
        else if (stored)
          void api<GitHubResult>(githubRoot + "/creations/" + stored.requestId)
            .then((value) => {
              if (active) setResult(value);
            })
            .catch((value) => {
              if (active) setError(failure(value));
            });
      })
      .catch((value) => {
        if (active) setError(failure(value));
      });
    return () => {
      active = false;
    };
  }, [storageKey]);

  useEffect(() => {
    if (!draft || !result || result.retryAfterSeconds <= 0) return;
    let active = true;
    const timer = window.setTimeout(() => {
      void api<GitHubResult>(githubRoot + "/creations/" + draft.requestId)
        .then((value) => {
          if (active) setResult(value);
        })
        .catch((value) => {
          if (active) setError(failure(value));
        });
    }, 5000);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [draft, result]);

  async function run(action: () => Promise<void>) {
    setPending(true);
    setError("");
    try {
      await action();
    } catch (value) {
      setError(failure(value));
    } finally {
      setPending(false);
    }
  }
  async function refresh() {
    const status = await api<GitHubStatus>(githubRoot);
    setConnection(status);
    setHistory(await api<GitHubHistory>(githubRoot + "/creations"));
    if (draft)
      setResult(
        await api<GitHubResult>(githubRoot + "/creations/" + draft.requestId),
      );
  }
  async function authorize() {
    if (!(await onBeforeLeave())) return;
    const destination = await api<{ url: string }>(githubRoot + "/start", {
      method: "POST",
    });
    window.location.assign(destination.url);
  }
  async function installationLink() {
    const destination = await api<{ url: string }>(
      githubRoot + "/installation",
      { method: "POST" },
    );
    setInstallation(destination.url);
  }
  async function continueRequest(value: GitHubRequest) {
    const latest =
      result?.requestId === value.requestId && result.repositoryId
        ? result
        : await api<GitHubResult>(githubRoot + "/creations", {
            method: "POST",
            body: value,
            timeoutMs: 180000,
          });
    setResult(latest);
    if (
      latest.repositoryId &&
      latest.stage !== "READY" &&
      latest.retryAfterSeconds === 0
    ) {
      setResult(
        await api<GitHubResult>(
          githubRoot + "/creations/" + value.requestId + "/resume",
          { method: "POST", timeoutMs: 180000 },
        ),
      );
    }
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!connection?.githubUserId || !connection.eligibleToCreate) return;
    const data = new FormData(event.currentTarget);
    const value = draft ?? {
      requestId: crypto.randomUUID(),
      displayName: String(data.get("displayName")).trim(),
      slug: String(data.get("slug")),
      githubOwnerId: connection.githubUserId,
      repositoryName: String(data.get("repositoryName")),
    };
    remember(value);
    void run(() => continueRequest(value));
  }
  function select(entry: GitHubEntry) {
    remember(entry.request);
    setResult(entry.result);
    setError("");
    setInstallation("");
  }
  const connected = connection?.state === "CONNECTED";
  const canResume =
    connection?.available &&
    connected &&
    (connection.eligibleToCreate || result?.workspaceCreated);

  return (
    <section className="sub-panel" aria-label="GitHub 个人空间">
      <h2>使用 GitHub 创建个人空间</h2>
      <p className="muted">
        在你自己的 GitHub
        个人账号下创建私有仓库。内容归你所有，空间的公开网站默认关闭。
      </p>
      {!connection && !error && <p role="status">正在读取 GitHub 连接…</p>}
      {connection?.available === false && (
        <p>站点尚未启用 GitHub 授权建仓，可以使用下方的手动连接。</p>
      )}
      {connection?.available && (
        <>
          {!connection.eligibleToCreate && (
            <p>
              创建新空间需要创作者资格。已有空间和原有授权仍按各自权限使用。
            </p>
          )}
          {connection.login && (
            <p>
              GitHub 账号：<strong>{connection.login}</strong>
            </p>
          )}
          <p className="muted">
            授权将请求仓库管理、内容读写及基本仓库信息权限，用于创建私有仓库和保存内容；连接
            GitHub 不会提升站点策略组，也不会成为本站登录方式。
          </p>
          <div className="button-row">
            {(connection.eligibleToCreate || connection.version > 0) && (
              <button disabled={pending} onClick={() => void run(authorize)}>
                {connected ? "重新授权 GitHub" : "授权 GitHub"}
              </button>
            )}
            {connected && (
              <button
                className="button-secondary"
                disabled={pending}
                onClick={() => void run(installationLink)}
              >
                管理 GitHub 仓库授权
              </button>
            )}
            {connection.version > 0 && connection.state !== "DISCONNECTED" && (
              <button
                className="text-button"
                disabled={pending}
                onClick={() => setDisconnecting(true)}
              >
                断开 GitHub 连接
              </button>
            )}
          </div>
          {connected && installation && (
            <p>
              <a href={installation} target="_blank" rel="noopener noreferrer">
                打开 GitHub 仓库授权设置
              </a>
              {draft && result?.repositoryId && !result.workspaceCreated
                ? `，选中仓库 ${draft.repositoryName}，保存后回来继续准备空间。`
                : "，选中需要连接的仓库并保存，再回到空间核对并恢复连接。"}
            </p>
          )}
          {disconnecting && (
            <div className="notice">
              <p>
                断开后，依赖此连接的空间将暂停同步。GitHub
                仓库和空间成员关系会保留。
              </p>
              <div className="button-row">
                <button
                  disabled={pending}
                  onClick={() =>
                    void run(async () => {
                      setConnection(
                        await api<GitHubStatus>(
                          githubRoot + "?version=" + connection.version,
                          { method: "DELETE" },
                        ),
                      );
                      setInstallation("");
                      setDisconnecting(false);
                    })
                  }
                >
                  确认断开
                </button>
                <button
                  className="text-button"
                  disabled={pending}
                  onClick={() => setDisconnecting(false)}
                >
                  取消
                </button>
              </div>
            </div>
          )}
          {connected && connection.eligibleToCreate && !draft && (
            <form onSubmit={submit}>
              <label>
                空间名称
                <input name="displayName" required maxLength={120} />
              </label>
              <label>
                空间地址
                <input
                  name="slug"
                  required
                  minLength={3}
                  maxLength={64}
                  pattern="[a-z0-9][a-z0-9\-]{1,62}[a-z0-9]"
                />
                <small>公开后使用 /s/空间地址。</small>
              </label>
              <label>
                GitHub 仓库名
                <input
                  name="repositoryName"
                  required
                  maxLength={100}
                  pattern="(?!(?:\.|\.\.|-)$)[A-Za-z0-9_.\-]{1,100}"
                />
                <small>
                  将创建在 {connection.login} 名下，请使用尚未占用的名称。
                </small>
              </label>
              <label className="oauth-permission">
                <input type="checkbox" required />
                <span>
                  我确认在 {connection.login} 的个人账号下创建一个私有仓库。
                </span>
              </label>
              <button disabled={pending}>创建私有仓库和空间</button>
            </form>
          )}
        </>
      )}
      {draft && (
        <div>
          <h3>{draft.displayName}</h3>
          <p>
            仓库名：{draft.repositoryName} · 空间地址：/s/{draft.slug}
          </p>
          {result ? (
            <p role="status">{githubStages[result.stage]}</p>
          ) : (
            <p>这次申请尚未取得结果，请查询或继续同一次申请。</p>
          )}
          {result?.failureCode && (
            <p className="notice">
              {githubFailures[result.failureCode] ??
                "操作尚未完成，请查询当前状态后继续。"}
            </p>
          )}
          {result?.repository && (
            <p>
              <a
                href={result.repository}
                target="_blank"
                rel="noopener noreferrer"
              >
                查看 GitHub 仓库
              </a>
            </p>
          )}
          <div className="button-row">
            {result?.stage === "READY" ? (
              <button
                disabled={pending}
                onClick={() =>
                  void run(async () => {
                    if (await onBeforeLeave()) {
                      await onCreated(result.workspaceId);
                      remember(null);
                      setResult(null);
                    }
                  })
                }
              >
                进入空间
              </button>
            ) : (
              <button
                disabled={
                  pending ||
                  !canResume ||
                  !!result?.retryAfterSeconds ||
                  result?.stage === "REJECTED"
                }
                onClick={() => void run(() => continueRequest(draft))}
              >
                {result?.repositoryId ? "继续准备空间" : "继续这次申请"}
              </button>
            )}
            {(result?.stage === "READY" || result?.stage === "REJECTED") && (
              <button
                className="text-button"
                disabled={pending}
                onClick={() => {
                  remember(null);
                  setResult(null);
                  setError("");
                }}
              >
                填写新的申请
              </button>
            )}
          </div>
        </div>
      )}
      <button
        className="text-button"
        disabled={pending}
        onClick={() => void run(refresh)}
      >
        刷新连接与申请状态
      </button>
      {history.items.length > 0 && (
        <details>
          <summary>之前的建仓申请</summary>
          <ul>
            {history.items.map((entry) => (
              <li key={entry.request.requestId}>
                <button
                  className="text-button"
                  disabled={pending}
                  onClick={() => select(entry)}
                >
                  {entry.request.displayName} · {entry.request.repositoryName} ·{" "}
                  {githubStages[entry.result.stage]}
                </button>
              </li>
            ))}
          </ul>
          {history.nextOffset !== null && (
            <button
              disabled={pending}
              onClick={() =>
                void run(async () => {
                  const page = await api<GitHubHistory>(
                    githubRoot + "/creations?offset=" + history.nextOffset,
                  );
                  setHistory({
                    items: [
                      ...history.items,
                      ...page.items.filter(
                        (entry) =>
                          !history.items.some(
                            (old) =>
                              old.request.requestId === entry.request.requestId,
                          ),
                      ),
                    ],
                    nextOffset: page.nextOffset,
                  });
                })
              }
            >
              更早的申请
            </button>
          )}
        </details>
      )}
      {pending && <p role="status">正在处理，请稍候…</p>}
      {notice && <p role="status">{notice}</p>}
      {error && (
        <p role="alert" className="notice danger">
          {error}
        </p>
      )}
    </section>
  );
}
