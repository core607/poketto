"use client";
import { useEffect, useState } from "react";
import { api } from "../lib/browser-api";
import { safeImage } from "../lib/format";
import { message } from "./admin";
import { AdminPagination } from "./admin-pagination";
import { Markdown } from "./markdown";
import { useSitePage } from "./site-page";

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
    gallery: { src: string; original: string; alt: string }[];
  };
};

export function SiteAccountSpaces({ accountId }: { accountId: string }) {
  const spaces = useSitePage<Space>(
    `/api/auth/site/accounts/${accountId}/workspaces`,
  );
  const [selected, setSelected] = useState<Space | null>(null);
  return (
    <section aria-label="拥有的空间">
      <h4>拥有的空间</h4>
      {spaces.error && <p role="alert">{spaces.error}</p>}
      {!spaces.loading &&
        !spaces.error &&
        spaces.items.map((space) => (
          <p key={space.workspaceId}>
            {space.displayName} ·{" "}
            {!space.eligible
              ? "展示受限"
              : space.enabled
                ? "网站已开启"
                : "网站已关闭"}{" "}
            <button onClick={() => setSelected(space)}>
              审阅 {space.displayName}
            </button>
          </p>
        ))}
      <AdminPagination label="账号拥有的空间" page={spaces} />
      {selected && (
        <SiteReview
          key={selected.workspaceId}
          space={selected}
          onClose={() => setSelected(null)}
        />
      )}
    </section>
  );
}

function SiteReview({ space, onClose }: { space: Space; onClose: () => void }) {
  const base = `/api/auth/site/workspaces/${space.workspaceId}/review`;
  const articles = useSitePage<Article>(base);
  const [selected, setSelected] = useState<Article | null>(null);
  return (
    <section
      className="sub-panel"
      aria-label={`${space.displayName} 的公开内容审阅`}
    >
      <h4>审阅 {space.displayName}</h4>
      <p>
        这里只显示当前符合仓库发布规则的内容及其引用媒体。网站关闭或展示受限时仍可审阅，私有文件和历史版本不在此范围内。
      </p>
      <button onClick={onClose}>关闭审阅</button>
      {articles.error && <p role="alert">{articles.error}</p>}
      {!articles.loading && !articles.error && articles.items.length === 0 && (
        <p>当前没有符合发布规则的内容。</p>
      )}
      {!articles.loading &&
        !articles.error &&
        articles.items.map((article) => (
          <p key={article.route}>
            <button onClick={() => setSelected(article)}>
              {article.title}
            </button>{" "}
            <small>{article.path}</small>
          </p>
        ))}
      <AdminPagination label="审阅文章" page={articles} />
      {selected && (
        <ReviewDocument
          key={selected.route}
          base={base}
          route={selected.route}
        />
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
  if (error) return <p role="alert">{error}</p>;
  if (!document) return <p role="status">正在读取审阅内容…</p>;
  return (
    <article className="sub-panel">
      <h3>{document.title}</h3>
      <Markdown
        source={document.media.body}
        images={document.media.images}
        downloads={document.media.downloads}
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
  const page = useSitePage<{ ownerName: string; reason: string | null }>(
    `${base}/restrictions`,
  );
  return (
    <div aria-label="公开展示限制原因">
      {page.error && <p role="alert">{page.error}</p>}
      {!page.loading &&
        !page.error &&
        page.items.map((item, index) => (
          <p key={index}>
            {item.ownerName}：{item.reason || "尚未获得创作者资格。"}
          </p>
        ))}
      <AdminPagination label="受限所有者" page={page} />
    </div>
  );
}
