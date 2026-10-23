"use client";
import { useEffect, useState } from "react";
import { api, message } from "../lib/browser-api";
import { safeImage } from "../lib/format";
import { AdminPagination, useAdminPage } from "./admin-pagination";
import { Markdown } from "./markdown";

type Space = {
  workspaceId: string;
  displayName: string;
  enabled: boolean;
  eligible: boolean;
};
type Article = { route: string; path: string; title: string };
type Review = {
  title: string;
  path: string;
  media: {
    body: string;
    images: Record<string, string>;
    downloads: Record<string, string>;
    playback?: Record<string, string>;
    gallery: { src: string; original: string; alt: string }[];
  };
};

export function SiteAccountSpaces({ accountId }: { accountId: string }) {
  const spaces = useAdminPage<Space>(
    `/api/auth/site/accounts/${accountId}/workspaces`,
  );
  const [selected, setSelected] = useState<Space | null>(null);
  if (selected)
    return (
      <SiteReview
        key={selected.workspaceId}
        space={selected}
        onClose={() => setSelected(null)}
      />
    );
  return (
    <section aria-label="拥有的空间" className="admin-block">
      <h3>拥有的空间</h3>
      {spaces.error && (
        <p role="alert" className="notice danger">
          {spaces.error}
        </p>
      )}
      {!spaces.loading && !spaces.error && spaces.items.length === 0 && (
        <p className="muted">这个账号没有拥有的空间。</p>
      )}
      <ul className="admin-list">
        {!spaces.loading &&
          !spaces.error &&
          spaces.items.map((space) => (
            <li key={space.workspaceId}>
              <span className="admin-list-main">
                <strong>{space.displayName}</strong>
                <SpaceState space={space} />
              </span>
              <button
                type="button"
                className="button-secondary"
                onClick={() => setSelected(space)}
              >
                审阅 {space.displayName}
              </button>
            </li>
          ))}
      </ul>
      <AdminPagination label="账号拥有的空间" page={spaces} />
    </section>
  );
}

/** A space's website state as moderation sees it. */
export function SpaceState({
  space,
}: {
  space: { enabled: boolean; eligible: boolean };
}) {
  const [label, tone] = !space.eligible
    ? ["展示受限", "warn"]
    : space.enabled
      ? ["网站已开启", "ok"]
      : ["网站已关闭", "off"];
  return <span className={`state-chip ${tone}`}>{label}</span>;
}

export function SiteReview({
  space,
  onClose,
}: {
  space: Pick<Space, "workspaceId" | "displayName">;
  onClose: () => void;
}) {
  const base = `/api/auth/site/workspaces/${space.workspaceId}/review`;
  const articles = useAdminPage<Article>(base);
  const [selected, setSelected] = useState<Article | null>(null);
  return (
    <section
      className="admin-block"
      aria-label={`${space.displayName} 的公开内容审阅`}
    >
      <div className="admin-block-head">
        <button
          type="button"
          className="text-button hover-underline"
          onClick={selected ? () => setSelected(null) : onClose}
        >
          ← {selected ? "返回文章列表" : "关闭审阅"}
        </button>
        <h3>审阅 {space.displayName}</h3>
      </div>
      {selected ? (
        <ReviewDocument
          key={selected.route}
          base={base}
          route={selected.route}
        />
      ) : (
        <>
          <p className="muted">
            这里只显示当前符合仓库发布规则的内容及其引用媒体。网站关闭或展示受限时仍可审阅，私有文件和历史版本不在此范围内。
          </p>
          {articles.error && (
            <p role="alert" className="notice danger">
              {articles.error}
            </p>
          )}
          {!articles.loading &&
            !articles.error &&
            articles.items.length === 0 && (
              <p className="muted">当前没有符合发布规则的内容。</p>
            )}
          <ul className="admin-list">
            {!articles.loading &&
              !articles.error &&
              articles.items.map((article) => (
                <li key={article.route}>
                  <span className="admin-list-main">
                    <button
                      type="button"
                      className="text-button hover-underline"
                      onClick={() => setSelected(article)}
                    >
                      {article.title}
                    </button>
                    <small className="muted">{article.path}</small>
                  </span>
                </li>
              ))}
          </ul>
          <AdminPagination label="审阅文章" page={articles} />
        </>
      )}
    </section>
  );
}

function ReviewDocument({ base, route }: { base: string; route: string }) {
  const [document, setDocument] = useState<Review | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    api<Review>(`${base}/document?route=${encodeURIComponent(route)}`)
      .then((value) => {
        if (active) setDocument(value);
      })
      .catch((failure) => {
        if (active) setError(message(failure));
      });
    return () => {
      active = false;
    };
  }, [base, route]);
  if (error)
    return (
      <p role="alert" className="notice danger">
        {error}
      </p>
    );
  if (!document)
    return (
      <p role="status" className="muted">
        正在读取审阅内容…
      </p>
    );
  return (
    <article className="review-document">
      <h4>{document.title}</h4>
      <p className="muted">{document.path}</p>
      <Markdown
        source={document.media.body}
        images={document.media.images}
        downloads={document.media.downloads}
        playback={document.media.playback}
        preview
      />
      <div className="image-grid">
        {document.media.gallery.map((image) => {
          const src = safeImage(image.src, true);
          const original = safeImage(image.original, true);
          return src && original ? (
            <a
              key={original}
              href={original}
              target="_blank"
              rel="noreferrer noopener"
            >
              <img src={src} alt={image.alt} loading="lazy" />
            </a>
          ) : null;
        })}
      </div>
    </article>
  );
}

export function PublicationRestrictions({ base }: { base: string }) {
  const page = useAdminPage<{
    ownerName: string;
    reason: string | null;
    suspended?: boolean;
  }>(`${base}/restrictions`);
  return (
    <div aria-label="公开展示限制原因">
      {page.error && <p role="alert">{page.error}</p>}
      {!page.loading &&
        !page.error &&
        page.items.map((item, index) => (
          <p key={index}>
            {item.ownerName}：
            {item.suspended
              ? "账号已被站点管理员停用。"
              : item.reason || "尚未获得创作者资格。"}
          </p>
        ))}
      <AdminPagination label="受限所有者" page={page} />
    </div>
  );
}
