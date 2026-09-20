"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../lib/browser-api";
import { message } from "./admin";
import { useConfirmation } from "./confirmation";

type Publication = {
  workspaceId: string;
  slug: string;
  displayName: string;
  publicAuthorName: string;
  enabled: boolean;
  eligible: boolean;
  effectiveEnabled: boolean;
};

export function SpacePublication({ workspaceId }: { workspaceId: string }) {
  const base = `/api/auth/workspaces/${encodeURIComponent(workspaceId)}/publication`;
  const confirm = useConfirmation();
  const [publication, setPublication] = useState<Publication | null>(null);
  const [author, setAuthor] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const epoch = useRef(0);
  const busy = useRef(false);

  async function read() {
    const version = ++epoch.current;
    busy.current = true;
    setPending(true);
    setPublication(null);
    setError("");
    setReceipt("");
    try {
      const value = await api<Publication>(base);
      if (version === epoch.current) {
        if (value.workspaceId !== workspaceId)
          throw new Error("空间状态不匹配");
        setPublication(value);
        setAuthor(value.publicAuthorName);
      }
    } catch (failure) {
      if (version === epoch.current) setError(message(failure));
    } finally {
      if (version === epoch.current) {
        busy.current = false;
        setPending(false);
      }
    }
  }

  useEffect(() => {
    void read();
    return () => {
      epoch.current++;
    };
  }, [workspaceId]);

  async function change() {
    if (!publication || busy.current) return;
    const version = epoch.current;
    const enabled = !publication.enabled;
    busy.current = true;
    setPending(true);
    setError("");
    setReceipt("");
    try {
      const approved = await confirm({
        title: enabled
          ? "开启这个空间的公开网站？"
          : "关闭这个空间的公开网站？",
        description: enabled
          ? "符合发布规则的公开内容将允许所有人访问。私密内容仍保持私密。"
          : "公开页面和图片链接将停止提供内容。空间成员仍可读取获准的文件，已被他人下载的副本无法撤回。",
        confirmLabel: enabled ? "开启公开网站" : "关闭公开网站",
      });
      if (!approved || version !== epoch.current) return;
      const value = await api<Publication>(base, {
        method: "PUT",
        body: { enabled },
      });
      if (version === epoch.current) {
        if (value.workspaceId !== workspaceId || value.enabled !== enabled)
          throw new Error("网站状态未确认");
        setPublication(value);
        setReceipt(
          enabled
            ? "公开网站已开启。页面可用性取决于发布规则和内容同步状态。"
            : "公开网站已关闭。成员访问保持不变。",
        );
      }
    } catch (failure) {
      if (version === epoch.current) {
        setPublication(null);
        setError(
          "未能确认网站当前状态，请重新读取后再操作。" + message(failure),
        );
      }
    } finally {
      if (version === epoch.current) {
        busy.current = false;
        setPending(false);
      }
    }
  }

  async function saveAuthor() {
    if (!publication || busy.current) return;
    const version = epoch.current;
    busy.current = true;
    setPending(true);
    setError("");
    setReceipt("");
    try {
      const value = await api<Publication>(base + "/author", {
        method: "PUT",
        body: { name: author },
      });
      if (version === epoch.current) {
        if (value.workspaceId !== workspaceId)
          throw new Error("空间状态不匹配");
        setPublication(value);
        setAuthor(value.publicAuthorName);
        setReceipt("公开署名已保存。");
      }
    } catch (failure) {
      if (version === epoch.current) {
        setPublication(null);
        setError(
          "未能确认署名当前状态，请重新读取后再操作。" + message(failure),
        );
      }
    } finally {
      if (version === epoch.current) {
        busy.current = false;
        setPending(false);
      }
    }
  }

  return (
    <section className="sub-panel" aria-label="网站发布">
      <h2>网站发布</h2>
      <p className="muted">
        开启后，符合发布规则的公开内容可以被所有人浏览。新空间默认关闭公开网站。
      </p>
      {publication && (
        <p>
          网站开关：<strong>{publication.enabled ? "已开启" : "已关闭"}</strong>
        </p>
      )}
      {receipt && (
        <p className="notice" role="status">
          {receipt}
        </p>
      )}
      {error && (
        <p className="notice danger" role="alert">
          {error}
        </p>
      )}
      {pending && <p role="status">正在处理网站状态…</p>}
      {publication ? (
        <button
          disabled={pending || (!publication.enabled && !publication.eligible)}
          onClick={() => void change()}
        >
          {publication.enabled ? "关闭公开网站" : "开启公开网站"}
        </button>
      ) : (
        <button disabled={pending} onClick={() => void read()}>
          重新读取网站状态
        </button>
      )}
      {publication && !publication.eligible && (
        <p className="notice" role="status">
          公开展示已受限：空间所有者的站点资格不满足展示要求。你仍可编辑和预览内容，完成整改后请联系管理员。
          恢复创作者资格后，已开启的网站会自动恢复；你也可以先关闭网站。
        </p>
      )}
      {publication?.effectiveEnabled && (
        <p>
          <a href={`/s/${encodeURIComponent(publication.slug)}`}>
            查看公开网站 ↗
          </a>
        </p>
      )}
      {publication && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void saveAuthor();
          }}
        >
          <label htmlFor={`public-author-${workspaceId}`}>空间公开署名</label>
          <input
            id={`public-author-${workspaceId}`}
            value={author}
            disabled={pending}
            maxLength={240}
            placeholder={publication.displayName}
            onChange={(event) => setAuthor(event.target.value)}
            aria-describedby={`public-author-help-${workspaceId}`}
          />
          <p id={`public-author-help-${workspaceId}`} className="muted">
            最多 120
            个字符。文章填写了公开署名时优先使用文章署名；这里留空时使用空间名称。
          </p>
          <button disabled={pending || author === publication.publicAuthorName}>
            保存公开署名
          </button>
        </form>
      )}
    </section>
  );
}
