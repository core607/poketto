import type { ArticlePage } from "../lib/types";
import { articleHref, date, spaceHref } from "../lib/format";
import {
  searchArticleHref,
  searchPath,
  type SearchContext,
} from "../lib/search-return";
import { SearchHighlight } from "./search-highlight";
import { SearchResults } from "./search-results";

export function ArticleList({
  page,
  base = "/",
  parameters = {},
  space,
  searchQuery,
}: {
  page: ArticlePage;
  base?: string;
  parameters?: Record<string, string>;
  space?: string;
  searchQuery?: string;
}) {
  const search: SearchContext | undefined =
    searchQuery && searchQuery.length <= 200
      ? { query: searchQuery, offset: page.offset, ...(space ? { space } : {}) }
      : undefined;
  const href = (route: string) =>
    search
      ? searchArticleHref(articleHref(route, space), search)
      : articleHref(route, space);
  const pageHref = (offset: number) =>
    base + "?" + new URLSearchParams({ ...parameters, offset: String(offset) });
  const content = (
    <>
      <div className="article-list">
        {page.items.length ? (
          page.items.map((item) => (
            <article
              key={item.route}
              className="article-card"
              id={
                search
                  ? "search-result-" +
                    encodeURIComponent(space ?? "") +
                    ":" +
                    encodeURIComponent(item.route)
                  : undefined
              }
            >
              <div className="article-meta">
                <span className="author-name">{item.authorName}</span>
                <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
                <span>／</span>
                {item.tags.slice(0, 3).map((tag) => (
                  <a
                    href={
                      spaceHref(space) + "/tags?tag=" + encodeURIComponent(tag)
                    }
                    key={tag}
                  >
                    {tag}
                  </a>
                ))}
              </div>
              <h2>
                <a
                  href={href(item.route)}
                  data-search-result={search ? "" : undefined}
                >
                  <SearchHighlight text={item.title} query={search?.query} />
                </a>
              </h2>
              <p>
                <SearchHighlight text={item.snippet} query={search?.query} />
              </p>
              <a
                className="read-link"
                href={href(item.route)}
                data-search-result={search ? "" : undefined}
              >
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
  return search ? (
    <SearchResults path={searchPath(search)}>{content}</SearchResults>
  ) : (
    content
  );
}
