"use client";

import { articleHref } from "../lib/format";
import type { RepositoryFile } from "../lib/types";

export function EditorPublicPage({
  file,
  path,
  dirty,
  pending,
  onRefresh,
}: {
  file: RepositoryFile;
  path: string;
  dirty: boolean;
  pending: boolean;
  onRefresh: () => void;
}) {
  const page = file.publicPage;
  if (file.expectedAbsence || path !== file.path)
    return (
      <p className="editor-public-state muted">保存后确认公开页面状态。</p>
    );

  if (!page)
    return (
      <div className="editor-public-state" aria-label="公开页面状态">
        <span role="status">公开状态待确认</span>
        <button
          type="button"
          className="text-button"
          disabled={pending}
          onClick={onRefresh}
        >
          {pending ? "正在查询…" : "重新查询状态"}
        </button>
      </div>
    );

  return (
    <div className="editor-public-state" aria-label="公开页面状态">
      <strong role="status">
        {
          {
            UNSAVED: "尚未保存",
            PRIVATE: "私有内容",
            WEBSITE_DISABLED: "尚未公开 · 网站未开启",
            WEBSITE_RESTRICTED: "公开展示已受限 · 仍可编辑整改",
            UNAVAILABLE: "公开页面暂不可用",
            SCHEDULED: "等待定时公开",
            AVAILABLE: "已公开",
          }[page.state]
        }
      </strong>
      {page.state === "SCHEDULED" && page.publishAt && (
        <span>定时发布：{scheduledTime(page.publishAt)}</span>
      )}
      {(page.state === "UNAVAILABLE" ||
        page.state === "WEBSITE_DISABLED" ||
        page.state === "WEBSITE_RESTRICTED") && (
        <button
          type="button"
          className="text-button"
          disabled={pending}
          onClick={onRefresh}
        >
          {pending ? "正在查询…" : "重新查询状态"}
        </button>
      )}
      {page.state === "AVAILABLE" && page.space && page.route && (
        <>
          <a
            href={articleHref(page.route, page.space)}
            target="_blank"
            rel="noopener noreferrer"
          >
            查看公开页面 ↗
          </a>
          {dirty && <span className="muted">公开页面显示已保存的版本。</span>}
        </>
      )}
    </div>
  );
}

function scheduledTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}
