import type { ArticlePage } from "../lib/types";
import { articleHref, date } from "../lib/format";

export function ArticleList({
  page,
  base = "/",
  parameters = {},
}: {
  page: ArticlePage;
  base?: string;
  parameters?: Record<string, string>;
}) {
  const pageHref = (offset: number) =>
    base + "?" + new URLSearchParams({ ...parameters, offset: String(offset) });
  return (
    <>
      <div className="article-list">
        {page.items.length ? (
          page.items.map((item) => (
            <article key={item.route} className="article-card">
              <div className="article-meta">
                <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
                <span>／</span>
                {item.tags.slice(0, 3).map((tag) => (
                  <a href={"/tags?tag=" + encodeURIComponent(tag)} key={tag}>
                    {tag}
                  </a>
                ))}
              </div>
              <h2>
                <a href={articleHref(item.route)}>{item.title}</a>
              </h2>
              <p>
                {item.snippet
                  .replace(/^#{1,6}\s+/gm, "")
                  .replace(/!\[[^\]]*\]\([^)]*\)/g, "[图片]")}
              </p>
              <a className="read-link" href={articleHref(item.route)}>
                继续阅读 <span aria-hidden>↗</span>
              </a>
            </article>
          ))
        ) : (
          <div className="empty-state">
            <span aria-hidden>✳</span>
            <h2>这里暂时安静。</h2>
            <p>还没有符合条件的公开文章。</p>
          </div>
        )}
      </div>
      <nav className="pagination" aria-label="文章翻页">
        {page.offset > 0 ? (
          <a href={pageHref(Math.max(0, page.offset - page.limit))}>← 上一页</a>
        ) : (
          <span />
        )}
        <span>共 {page.total} 篇</span>
        {page.offset + page.limit < page.total ? (
          <a href={pageHref(page.offset + page.limit)}>下一页 →</a>
        ) : (
          <span />
        )}
      </nav>
    </>
  );
}
