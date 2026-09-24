"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError } from "../lib/browser-api";
import { message } from "./admin";
import { GitHubRepositoryReconnection } from "./github-repository-reconnection";

type ConnectionInfo = {
  managed: boolean;
  rotationAvailable: boolean;
  githubApp: boolean;
  binding: { repository: string; updatedAt: string } | null;
};
type Initialization = { repositoryEmpty: boolean; missingFiles: string[] };
type InitializationOutcome = { commit: string; addedFiles: string[] };

const rotationErrors: Record<string, string> = {
  PERMISSION_DENIED:
    "新令牌未通过权限验证。请确认它能读取仓库信息，并拥有 Git 读写权限。",
  PRIVATE_REPOSITORY_REQUIRED:
    "已连接的仓库必须保持私有，请检查 Git 托管平台的仓库设置。",
  REPOSITORY_CHANGED: "仓库连接或凭据已发生变化，请重新读取连接信息后再更新。",
  INVALID_INPUT: "请检查 Git 用户名和新令牌是否填写完整。",
};

// The folders each set adds are listed in content-template/sets; "general" adds none.
const TEMPLATES = [
  {
    slug: "general",
    label: "通用",
    description: "只有私有区、公开区和它们的说明文件。",
  },
  {
    slug: "journal",
    label: "周记与日记",
    description: "journal 文件夹：按天或按周记一篇。",
  },
  {
    slug: "reading-notes",
    label: "读书笔记",
    description: "reading 文件夹：每本书一篇，摘录和想法分开写。",
  },
  {
    slug: "albums",
    label: "相册",
    description: "albums 文件夹：每个相册一个目录，同目录照片自动组成图库。",
  },
  {
    slug: "digest",
    label: "新闻摘编",
    description: "digest 文件夹：每条新闻一篇，写明要点和来源。",
  },
];

function templateQuery(template: string) {
  return template === "general" ? "" : "?" + new URLSearchParams({ template });
}

export function RepositoryConnection({ workspaceId }: { workspaceId: string }) {
  const base = `/api/auth/workspaces/${encodeURIComponent(workspaceId)}`;
  const [connection, setConnection] = useState<ConnectionInfo | null>(null);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const [pending, setPending] = useState(false);
  const [initialization, setInitialization] = useState<Initialization | null>(
    null,
  );
  const [initializationError, setInitializationError] = useState("");
  const [initializing, setInitializing] = useState(false);
  const [template, setTemplate] = useState("general");
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
  async function loadInitialization(chosen = template) {
    try {
      const result = await api<Initialization>(
        base + "/repository-initialization" + templateQuery(chosen),
      );
      if (active.current) {
        setInitialization(result);
        setInitializationError("");
      }
    } catch (failure) {
      if (active.current) setInitializationError(message(failure));
    }
  }
  useEffect(() => {
    active.current = true;
    void load();
    void loadInitialization();
    return () => {
      active.current = false;
    };
  }, []);

  async function initialize() {
    if (initializing) return;
    setInitializing(true);
    setInitializationError("");
    setReceipt("");
    try {
      const outcome = await api<InitializationOutcome>(
        base + "/repository-initialization",
        {
          method: "POST",
          timeoutMs: 90000,
          ...(template === "general" ? {} : { body: { template } }),
        },
      );
      if (active.current) {
        setReceipt(
          outcome.addedFiles.length
            ? `已写入 ${outcome.addedFiles.length} 个文件，提交 ${outcome.commit.slice(0, 12)}。`
            : "仓库已经包含全部指引文件，没有新的写入。",
        );
        await loadInitialization();
      }
    } catch (failure) {
      if (active.current) setInitializationError(message(failure));
    } finally {
      if (active.current) setInitializing(false);
    }
  }

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
      <h2>当前连接</h2>
      <p className="muted">
        这个空间的全部内容和修改历史都保存在下面这个 Git 仓库里。Poketto
        负责读写；懂 Git 的话，你也可以用任何 Git 工具直接编辑它。
      </p>
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
          {connection.githubApp ? (
            <GitHubRepositoryReconnection
              key={workspaceId}
              workspaceId={workspaceId}
              repository={connection.binding.repository}
              onReconnected={async () => {
                await load();
                await loadInitialization();
              }}
            />
          ) : !connection.rotationAvailable ? (
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
      {connection && (
        <div aria-label="仓库指引文件">
          <h3>给 AI 助手的说明文件</h3>
          <p className="muted">
            这几个文件告诉 AI 助手这个空间怎样组织内容、哪里是草稿、哪里会公开。
          </p>
          {initializationError && (
            <p className="notice danger" role="alert">
              {initializationError}{" "}
              <button
                disabled={initializing}
                onClick={() => void loadInitialization()}
              >
                重新检查
              </button>
            </p>
          )}
          <fieldset className="template-choices" disabled={initializing}>
            <legend>空间用途</legend>
            {TEMPLATES.map((choice) => (
              <label key={choice.slug} className="template-choice">
                <input
                  type="radio"
                  name="template"
                  value={choice.slug}
                  checked={template === choice.slug}
                  onChange={() => {
                    setTemplate(choice.slug);
                    setInitialization(null);
                    setReceipt("");
                    void loadInitialization(choice.slug);
                  }}
                />
                <span>
                  <strong>{choice.label}</strong>
                  <span className="muted">{choice.description}</span>
                </span>
              </label>
            ))}
          </fieldset>
          {!initialization && !initializationError && (
            <p role="status">正在检查仓库指引文件…</p>
          )}
          {initialization && initialization.missingFiles.length === 0 && (
            <p>
              {template === "general"
                ? "仓库已包含内容模板的指引文件和发布策略，不需要初始化。"
                : "仓库已包含这个模板的全部文件。"}
            </p>
          )}
          {initialization && initialization.missingFiles.length > 0 && (
            <>
              <p>
                {initialization.repositoryEmpty
                  ? "仓库还没有任何提交。写入下面的文件会成为它的第一个提交，发布保持关闭："
                  : "仓库缺少内容模板中的以下文件。写入只会添加这些文件，不修改、不移动任何已有内容，也不会开启发布："}
              </p>
              <ul>
                {initialization.missingFiles.map((file) => (
                  <li key={file}>
                    <code>{file}</code>
                  </li>
                ))}
              </ul>
              <button
                disabled={initializing || pending}
                onClick={() => void initialize()}
              >
                {initializing ? "正在写入…" : "写入这些文件"}
              </button>
            </>
          )}
        </div>
      )}
    </section>
  );
}
