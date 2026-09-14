import { redirect } from "next/navigation";
import { discovery, PublicApiError } from "../lib/public-api";
import { articleHref, date, spaceHref } from "../lib/format";
import { DiscoveryCover } from "../components/discovery-cover";

type Parameters = {
  batch?: string | string[];
  afterBatch?: string | string[];
  offset?: string | string[];
};
export default async function Home({
  searchParams,
}: {
  searchParams: Promise<Parameters>;
}) {
  const parameters = await searchParams;
  const batch =
    typeof parameters.batch === "string" ? parameters.batch : undefined;
  const afterBatch =
    typeof parameters.afterBatch === "string"
      ? parameters.afterBatch
      : undefined;
  const offset =
    typeof parameters.offset === "string" ? parameters.offset : "0";
  const page = await discovery({
    ...(batch ? { batch, offset } : afterBatch ? { afterBatch } : {}),
  }).catch((error) => {
    if (error instanceof PublicApiError && error.status === 410) return null;
    throw error;
  });
  if (!page)
    return (
      <section className="page-shell empty-state">
        <h1>换一批，继续发现。</h1>
        <p>这批浏览记录已过期，可以重新开始。</p>
        <a href="/">开始新一批 ↗</a>
      </section>
    );
  if (!batch) redirect("/?" + new URLSearchParams({ batch: page.batch }));
  const pageHref = (position: number) =>
    "/?" + new URLSearchParams({ batch: page.batch, offset: String(position) });
  return (
    <div className="page-shell">
      <section className="hero">
        <div>
          <p className="eyebrow">来自不同空间的记录</p>
          <h1>
            今天，发现一点新东西<span className="accent">。</span>
          </h1>
          <p className="hero-description">
            随意翻翻，也许会遇见想要留下的一页。
          </p>
        </div>
        <div className="hero-aside">
          <a href={"/?" + new URLSearchParams({ afterBatch: page.batch })}>
            换一批 ↻
          </a>
          <a href="/admin">我的空间 ↗</a>
        </div>
      </section>
      <div className="article-list">
        {page.items.map((item) => (
          <article className="article-card" key={item.space + ":" + item.route}>
            {item.album && (
              <DiscoveryCover
                src={item.cover}
                href={articleHref(item.route, item.space)}
                title={item.title}
              />
            )}
            <div className="article-meta">
              <a href={spaceHref(item.space)}>{item.spaceName}</a>
              <span className="author-name">{item.authorName}</span>
              <span>／</span>
              <span>
                {item.album || item.collection
                  ? [item.album && "相册", item.collection && "合集"]
                      .filter(Boolean)
                      .join(" · ")
                  : item.folderPage
                    ? "目录"
                    : "文章"}
              </span>
              <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
            </div>
            <h2>
              <a href={articleHref(item.route, item.space)}>{item.title}</a>
            </h2>
            <p>{item.snippet}</p>
            <div className="tag-row">
              {item.tags.map((tag) => (
                <a
                  href={
                    spaceHref(item.space) +
                    "/tags?tag=" +
                    encodeURIComponent(tag)
                  }
                  key={tag}
                >
                  {tag}
                </a>
              ))}
            </div>
            <a className="read-link" href={articleHref(item.route, item.space)}>
              {item.album
                ? "打开相册"
                : item.collection
                  ? "浏览合集"
                  : "继续阅读"}{" "}
              ↗
            </a>
          </article>
        ))}
        {!page.items.length && (
          <div className="empty-state">
            <h2>这一页暂时安静。</h2>
            <p>内容可能还在同步，或已不再公开。试试下一页，或者换一批。</p>
          </div>
        )}
      </div>
      <nav className="pagination" aria-label="发现翻页">
        {page.previousOffset !== null ? (
          <a href={pageHref(page.previousOffset)}>← 上一页</a>
        ) : (
          <span />
        )}
        <span>第 {Math.floor(page.offset / page.limit) + 1} 页</span>
        {page.nextOffset !== null ? (
          <a href={pageHref(page.nextOffset)}>下一页 →</a>
        ) : (
          <a href={"/?" + new URLSearchParams({ afterBatch: page.batch })}>
            换一批 ↻
          </a>
        )}
      </nav>
    </div>
  );
}
