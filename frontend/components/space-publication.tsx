"use client";

import { useEffect, useRef, useState } from "react";
import { api, message } from "../lib/browser-api";
import { useConfirmation } from "./confirmation";

import { PublicationRestrictions } from "./site-review";

type Publication = {
  workspaceId: string;
  slug: string;
  displayName: string;
  publicAuthorName: string;
  publicDescription: string;
  enabled: boolean;
  eligible: boolean;
  effectiveEnabled: boolean;
  publicHistory: boolean;
};

export function SpacePublication({
  workspaceId,
  onRenamed,
}: {
  workspaceId: string;
  /** Called once the server confirms a new space name, so lists showing it can reread. */
  onRenamed?: () => void;
}) {
  const base = `/api/auth/workspaces/${encodeURIComponent(workspaceId)}/publication`;
  const confirm = useConfirmation();
  const [publication, setPublication] = useState<Publication | null>(null);
  const [author, setAuthor] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState("");
  const [profileNotice, setProfileNotice] = useState<{
    failed: boolean;
    text: string;
  } | null>(null);
  const epoch = useRef(0);
  const busy = useRef(false);

  async function read() {
    const version = ++epoch.current;
    busy.current = true;
    setPending(true);
    setPublication(null);
    setError("");
    setReceipt("");
    setProfileNotice(null);
    try {
      const value = await api<Publication>(base);
      if (version === epoch.current) {
        if (value.workspaceId !== workspaceId)
          throw new Error("空间状态不匹配");
        setPublication(value);
        setAuthor(value.publicAuthorName);
        setName(value.displayName);
        setDescription(value.publicDescription);
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
          ? "「已发布」里符合发布规则的笔记，所有人都能访问；「草稿」始终只有获准查看草稿的成员能看到。"
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

  async function changeHistory() {
    if (!publication || busy.current) return;
    const version = epoch.current;
    const shown = !publication.publicHistory;
    busy.current = true;
    setPending(true);
    setError("");
    setReceipt("");
    try {
      const approved = await confirm({
        title: shown ? "公开文章的修订历史？" : "隐藏文章的修订历史？",
        description: shown
          ? "读者可以看到每篇文章公开以来的历次正文，并比较改了什么，包括后来删掉的段落。提交说明、作者和草稿期间的内容不会公开。"
          : "所有文章的修订历史页面会立即停止提供，已被他人保存的副本无法撤回。",
        confirmLabel: shown ? "公开修订历史" : "隐藏修订历史",
      });
      if (!approved || version !== epoch.current) return;
      const value = await api<Publication>(base + "/history", {
        method: "PUT",
        body: { shown },
      });
      if (version === epoch.current) {
        if (value.workspaceId !== workspaceId || value.publicHistory !== shown)
          throw new Error("修订历史状态未确认");
        setPublication(value);
        setReceipt(shown ? "修订历史已公开。" : "修订历史已隐藏。");
      }
    } catch (failure) {
      if (version === epoch.current) {
        setPublication(null);
        setError(
          "未能确认修订历史当前状态，请重新读取后再操作。" + message(failure),
        );
      }
    } finally {
      if (version === epoch.current) {
        busy.current = false;
        setPending(false);
      }
    }
  }

  /**
   * Saves each changed profile field in turn. Every confirmed answer becomes the displayed state at
   * once, so a failure part-way keeps the fields already saved and leaves the rest in the form.
   */
  async function saveProfile() {
    if (!publication || busy.current) return;
    const version = epoch.current;
    const changes = [
      name.trim() !== publication.displayName && {
        label: "空间名称",
        path: "/name",
        body: { text: name },
      },
      description.trim() !== publication.publicDescription && {
        label: "空间简介",
        path: "/description",
        body: { text: description },
      },
      author.trim() !== publication.publicAuthorName && {
        label: "公开署名",
        path: "/author",
        body: { name: author },
      },
    ].filter(Boolean) as {
      label: string;
      path: string;
      body: { text?: string; name?: string };
    }[];
    if (!changes.length) return;
    busy.current = true;
    setPending(true);
    setError("");
    setReceipt("");
    setProfileNotice(null);
    const saved: string[] = [];
    try {
      for (const { label, path, body } of changes) {
        const value = await api<Publication>(base + path, {
          method: "PUT",
          body,
        });
        if (version !== epoch.current) return;
        if (value.workspaceId !== workspaceId) {
          setPublication(null);
          setError("未能确认网站资料当前状态，请重新读取后再操作。");
          return;
        }
        setPublication(value);
        saved.push(label);
        if (path === "/name") {
          setName(value.displayName);
          onRenamed?.();
        }
        if (path === "/description") setDescription(value.publicDescription);
        if (path === "/author") setAuthor(value.publicAuthorName);
      }
      setProfileNotice({ failed: false, text: "网站资料已保存。" });
    } catch (failure) {
      if (version === epoch.current)
        setProfileNotice({
          failed: true,
          text:
            (saved.length ? `${saved.join("、")}已保存；` : "") +
            `${changes
              .slice(saved.length)
              .map((change) => change.label)
              .join("、")}没有保存，你的修改还留在上面，可以再保存一次。` +
            message(failure),
        });
    } finally {
      if (version === epoch.current) {
        busy.current = false;
        setPending(false);
      }
    }
  }
  const signature = author.trim() || name.trim() || publication?.displayName;
  const changed =
    publication &&
    (name.trim() !== publication.displayName ||
      description.trim() !== publication.publicDescription ||
      author.trim() !== publication.publicAuthorName);
  return (
    <div className="management-panel">
      <section className="sub-panel" aria-label="公开网站">
        <div className="panel-heading">
          <h2>公开网站</h2>
          {publication?.effectiveEnabled && (
            <a href={`/s/${encodeURIComponent(publication.slug)}`}>
              查看公开网站
            </a>
          )}
        </div>
        <p className="muted">
          开启后，放在「已发布」里的内容会出现在这个空间的网站上，所有人都能看到；草稿始终只有获准查看草稿的成员能看到。新空间默认关闭公开网站。
        </p>
        {publication && (
          <p>
            网站开关：
            <strong>{publication.enabled ? "已开启" : "已关闭"}</strong>
            {publication.effectiveEnabled && (
              <span className="muted"> · 网址 /s/{publication.slug}</span>
            )}
          </p>
        )}
        {receipt && (
          <p className="notice success" role="status">
            {receipt}
          </p>
        )}
        {error && (
          <p className="notice danger" role="alert">
            {error}
          </p>
        )}
        {pending && (
          <p role="status" className="muted">
            正在处理网站状态…
          </p>
        )}
        {publication ? (
          <button
            disabled={
              pending || (!publication.enabled && !publication.eligible)
            }
            onClick={() => void change()}
          >
            {publication.enabled ? "关闭公开网站" : "开启公开网站"}
          </button>
        ) : (
          <button disabled={pending} onClick={() => void read()}>
            重新读取网站状态
          </button>
        )}
        {publication && (
          <div className="history-setting">
            <p>
              修订历史：
              <strong>{publication.publicHistory ? "公开" : "不公开"}</strong>
              <span className="muted">
                {" "}
                · 公开后，文章底部会链接到它公开以来的历次正文。
              </span>
            </p>
            <button disabled={pending} onClick={() => void changeHistory()}>
              {publication.publicHistory ? "隐藏修订历史" : "公开修订历史"}
            </button>
          </div>
        )}
        {publication && !publication.eligible && (
          <div className="notice" role="status">
            <p>
              公开展示已受限：空间所有者的站点资格不满足展示要求。你仍可编辑和预览内容，完成整改后请联系管理员。
              恢复创作者资格后，已开启的网站会自动恢复；你也可以先关闭网站。
            </p>
            <PublicationRestrictions key={workspaceId} base={base} />
          </div>
        )}
      </section>
      {publication && (
        <section
          className="sub-panel"
          aria-labelledby={`profile-${workspaceId}`}
        >
          <div className="panel-heading">
            <h2 id={`profile-${workspaceId}`}>网站资料</h2>
          </div>
          <div className="profile-editor">
            <form
              onSubmit={(event) => {
                event.preventDefault();
                void saveProfile();
              }}
            >
              <label>
                空间名称
                <input
                  value={name}
                  disabled={pending}
                  required
                  maxLength={120}
                  onChange={(event) => setName(event.target.value)}
                />
                <span className="form-help">
                  这个空间叫什么。显示在网站标题、首页发现卡片和工作台里。
                </span>
              </label>
              <label>
                空间简介
                <textarea
                  value={description}
                  disabled={pending}
                  rows={3}
                  maxLength={280}
                  placeholder="一两句话，介绍这里写些什么。"
                  onChange={(event) => setDescription(event.target.value)}
                />
                <span className="form-help">
                  显示在网站首页的空间名称下方，最多 280 字，可以留空。
                </span>
              </label>
              <label htmlFor={`public-author-${workspaceId}`}>
                公开署名
                <input
                  id={`public-author-${workspaceId}`}
                  value={author}
                  disabled={pending}
                  maxLength={240}
                  placeholder={name || publication.displayName}
                  onChange={(event) => setAuthor(event.target.value)}
                  aria-describedby={`public-author-help-${workspaceId}`}
                />
                <span
                  id={`public-author-help-${workspaceId}`}
                  className="form-help"
                >
                  文章「作者」一栏显示的名字，比如你的笔名。留空时直接用空间名称。某篇文章想换个署名，可以在它开头写{" "}
                  <code>public_author: 名字</code>。
                </span>
              </label>
              {profileNotice && (
                <p
                  className={`notice ${profileNotice.failed ? "danger" : "success"}`}
                  role={profileNotice.failed ? "alert" : "status"}
                >
                  {profileNotice.text}
                </p>
              )}
              <button disabled={pending || !changed || !name.trim()}>
                保存网站资料
              </button>
            </form>
            <aside className="profile-preview" aria-label="网站上的样子">
              <p className="eyebrow">网站上会这样显示</p>
              <strong>{name.trim() || publication.displayName}</strong>
              {description.trim() && <p>{description.trim()}</p>}
              <p className="muted">文章署名：{signature}</p>
            </aside>
          </div>
        </section>
      )}
    </div>
  );
}
