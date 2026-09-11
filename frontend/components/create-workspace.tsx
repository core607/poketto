"use client";
import { type FormEvent, useEffect, useState } from "react";
import { api, ApiError } from "../lib/browser-api";
import { message } from "./admin";

type Draft = {
  requestId: string;
  displayName: string;
  slug: string;
  repository: string;
};
type Result = {
  workspaceId: string;
  stage: "VALIDATING" | "FAILED" | "READY";
  failureCode: string | null;
  retryAfterSeconds: number;
};
const failures: Record<string, string> = {
  PRIVATE_REPOSITORY_REQUIRED:
    "请连接私有仓库，避免私密文件通过 Git 托管站点泄露。",
  PERMISSION_DENIED: "令牌需要仓库信息读取和 Git 读写权限，请检查后重试。",
  DUPLICATE: "空间地址或仓库已经被使用，请查看已有空间。",
  INVALID_INPUT: "请检查仓库地址；已有内容的仓库需要 main 分支。",
  UNAVAILABLE: "暂时无法连接仓库。可以保留这次申请，稍后重试。",
};

export function CreateWorkspace({
  accountId,
  onCreated,
}: {
  accountId: string;
  onCreated: (workspace: string) => Promise<void>;
}) {
  const storageKey = "poketto.workspace-creation." + accountId;
  const [available, setAvailable] = useState<boolean | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function status(id: string) {
    try {
      const value = await api<Result>(
        `/api/auth/workspaces/creations/${encodeURIComponent(id)}`,
      );
      setResult(value);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 404) clearDraft();
      throw failure;
    }
  }
  function clearDraft() {
    setDraft(null);
    setResult(null);
    try {
      sessionStorage.removeItem(storageKey);
    } catch {
      /* Optional browser storage. */
    }
  }
  useEffect(() => {
    let active = true;
    void api<{ available: boolean }>("/api/auth/workspaces/creation-policy")
      .then((policy) => {
        if (active) setAvailable(policy.available);
      })
      .catch((failure) => {
        if (active) setError(message(failure));
      });
    try {
      const stored = sessionStorage.getItem(storageKey);
      const value = stored ? JSON.parse(stored) : null;
      if (
        value &&
        typeof value.requestId === "string" &&
        /^[0-9a-f-]{36}$/.test(value.requestId) &&
        [value.displayName, value.slug, value.repository].every(
          (item) => typeof item === "string" && item.length <= 2048,
        )
      ) {
        setDraft(value);
        void api<Result>(`/api/auth/workspaces/creations/${value.requestId}`)
          .then((receipt) => {
            if (active) setResult(receipt);
          })
          .catch((failure) => {
            if (active) setError(message(failure));
          });
      }
    } catch {
      /* Storage is optional; the durable request remains on the server. */
    }
    return () => {
      active = false;
    };
  }, [storageKey]);
  useEffect(() => {
    if (
      !draft ||
      result?.stage !== "VALIDATING" ||
      result.retryAfterSeconds === 0
    )
      return;
    let active = true;
    const timer = window.setTimeout(() => {
      void api<Result>(`/api/auth/workspaces/creations/${draft.requestId}`)
        .then((value) => {
          if (active) setResult(value);
        })
        .catch((failure) => {
          if (active) setError(message(failure));
        });
    }, 5000);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [draft, result]);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const value = draft ?? {
      requestId: crypto.randomUUID(),
      displayName: String(data.get("displayName")),
      slug: String(data.get("slug")),
      repository: String(data.get("repository")),
    };
    const token = String(data.get("token") ?? "");
    const username = String(data.get("username") ?? "");
    setDraft(value);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(value));
    } catch {
      /* The current tab can still retry the same request. */
    }
    setPending(true);
    setError("");
    try {
      const receipt = await api<Result>("/api/auth/workspaces/creations", {
        method: "POST",
        timeoutMs: 180000,
        body: {
          ...value,
          username: token ? username : null,
          token: token || null,
        },
      });
      setResult(receipt);
      const tokenField = form.elements.namedItem("token");
      if (tokenField instanceof HTMLInputElement) tokenField.value = "";
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 400) clearDraft();
      setError(message(failure));
    } finally {
      setPending(false);
    }
  }
  async function open() {
    if (!result || result.stage !== "READY") return;
    await onCreated(result.workspaceId);
    try {
      sessionStorage.removeItem(storageKey);
    } catch {
      /* No credential is stored here. */
    }
  }
  return (
    <section className="sub-panel" aria-label="创建空间">
      <h2>创建自己的空间</h2>
      <p className="muted">
        连接已有的 GitHub 或 CNB
        私有仓库。创建后仅空间成员可以访问；公开网站默认关闭。
      </p>
      {available === false && <p>站点尚未启用仓库连接，请联系站点管理员。</p>}
      {available && result?.stage !== "READY" && (
        <form onSubmit={submit}>
          <label>
            空间名称
            <input
              name="displayName"
              required
              maxLength={80}
              defaultValue={draft?.displayName}
              key={draft?.requestId + "name"}
              readOnly={!!draft}
            />
          </label>
          <label>
            公开地址
            <input
              name="slug"
              required
              pattern="[a-z0-9][a-z0-9-]{1,62}[a-z0-9]"
              minLength={3}
              maxLength={64}
              defaultValue={draft?.slug}
              key={draft?.requestId + "slug"}
              readOnly={!!draft}
            />
            <small>例如 my-notes，以后公开时使用 /s/my-notes。</small>
          </label>
          <label>
            仓库 HTTPS 地址
            <input
              name="repository"
              type="url"
              required
              maxLength={2048}
              placeholder="https://cnb.cool/你的组织/你的仓库"
              defaultValue={draft?.repository}
              key={draft?.requestId + "repo"}
              readOnly={!!draft}
            />
          </label>
          <label>
            Git 用户名
            <input
              name="username"
              defaultValue="cnb"
              autoComplete="off"
              maxLength={256}
            />
            <small>CNB 使用 cnb；GitHub 使用你的 GitHub 用户名。</small>
          </label>
          <label>
            仓库访问令牌
            <input
              name="token"
              type="password"
              autoComplete="new-password"
              required={!draft}
              maxLength={2048}
            />
            <small>
              需要读取仓库信息和 Git 读写权限。令牌不会存进浏览器历史或草稿。
            </small>
          </label>
          <button
            disabled={
              pending ||
              (result?.stage === "VALIDATING" && result.retryAfterSeconds > 0)
            }
          >
            {pending ? "正在连接仓库…" : draft ? "重试这次申请" : "创建空间"}
          </button>
          {draft && (
            <button
              type="button"
              className="button-secondary"
              disabled={pending}
              onClick={() =>
                void status(draft.requestId).catch((failure) =>
                  setError(message(failure)),
                )
              }
            >
              查询结果
            </button>
          )}
          {draft && result?.stage === "FAILED" && (
            <button
              type="button"
              className="text-button"
              disabled={pending}
              onClick={() => {
                setDraft(null);
                setResult(null);
                setError("");
                try {
                  sessionStorage.removeItem(storageKey);
                } catch {
                  /* Optional browser storage. */
                }
              }}
            >
              填写另一份申请
            </button>
          )}
        </form>
      )}
      {result?.stage === "VALIDATING" && (
        <p role="status">
          正在验证仓库；这次申请会保留，返回页面后可以继续查询。
        </p>
      )}
      {result?.stage === "FAILED" && (
        <p className="notice danger" role="alert">
          {failures[result.failureCode ?? ""] ?? "连接没有完成，请检查后重试。"}
        </p>
      )}
      {result?.stage === "READY" && (
        <p role="status">
          空间已创建，公开网站尚未开启。
          <button
            onClick={() =>
              void open().catch((failure) => setError(message(failure)))
            }
          >
            打开空间
          </button>
        </p>
      )}
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
