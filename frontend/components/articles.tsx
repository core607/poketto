import type { ArticlePage, ArticleSummary } from "../lib/types";
import { articleHref, date, spaceHref, tagHref } from "../lib/format";
import {
  searchArticleHref,
  searchPath,
  type SearchContext,
} from "../lib/search-return";
import { SearchHighlight } from "./search-highlight";
import { SearchResults } from "./search-results";
import { Avatar, Icon } from "./ui/icons";
import { Pager } from "./ui/pager";

type ListedArticle = ArticleSummary & { space?: string; spaceName?: string };
type ListedPage = Pick<ArticlePage, "total" | "offset" | "limit"> &
  Partial<Pick<ArticlePage, "commit" | "verifiedAt" | "expiresAt">> & {
    items: ListedArticle[];
  };

export function ArticleList({
  page,
  base = "/",
  parameters = {},
  space,
  searchQuery,
  spaceName,
  empty = "还没有符合条件的公开文章。",
}: {
  page: ListedPage;
  base?: string;
  parameters?: Record<string, string>;
  space?: string;
  searchQuery?: string;
  /** The listing space's name; matching author signatures are not repeated. */
  spaceName?: string;
  empty?: string;
}) {
  const search: SearchContext | undefined =
    searchQuery && searchQuery.length <= 200
      ? { query: searchQuery, offset: page.offset, ...(space ? { space } : {}) }
      : undefined;
  const href = (item: ListedArticle) =>
    search
      ? searchArticleHref(articleHref(item.route, item.space ?? space), search)
      : articleHref(item.route, item.space ?? space);
  const pageHref = (offset: number) =>
    base + "?" + new URLSearchParams({ ...parameters, offset: String(offset) });
  const pages = Math.max(1, Math.ceil(page.total / page.limit));
  const content = (
    <>
      {page.items.length ? (
        <div className="entry-list">
          {page.items.map((item) => (
            <article
              key={(item.space ?? space ?? "") + ":" + item.route}
              className="entry article-card"
              id={
                search
                  ? "search-result-" +
                    encodeURIComponent(item.space ?? space ?? "") +
                    ":" +
                    encodeURIComponent(item.route)
                  : undefined
              }
            >
              <div className="card-meta">
                {item.space && item.spaceName && (
                  <a className="space-link" href={spaceHref(item.space)}>
                    <Avatar name={item.spaceName} />
                    {item.spaceName}
                  </a>
                )}
                {item.authorName !== (item.spaceName ?? spaceName) && (
                  <span className="author-name">{item.authorName}</span>
                )}
                <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
                {item.tags.slice(0, 3).map((tag) => (
                  <a
                    className="tag"
                    href={tagHref(tag, item.space ?? space)}
                    key={tag}
                  >
                    #{tag}
                  </a>
                ))}
              </div>
              <h2>
                <a
                  href={href(item)}
                  data-search-result={search ? "" : undefined}
                >
                  <SearchHighlight text={item.title} query={search?.query} />
                </a>
              </h2>
              {item.snippet && (
                <p>
                  <SearchHighlight text={item.snippet} query={search?.query} />
                </p>
              )}
            </article>
          ))}
        </div>
      ) : (
        <div className="empty">
          <span className="empty-mark">
            <Icon name="sparkle" />
          </span>
          <h2>这里暂时安静。</h2>
          <p>{empty}</p>
        </div>
      )}
      <Pager
        label="文章翻页"
        previous={
          page.offset > 0
            ? pageHref(Math.max(0, page.offset - page.limit))
            : null
        }
        next={
          page.offset + page.limit < page.total
            ? pageHref(page.offset + page.limit)
            : null
        }
        status={`${Math.floor(page.offset / page.limit) + 1} / ${pages}`}
      />
    </>
  );
  return search ? (
    <SearchResults path={searchPath(search)}>{content}</SearchResults>
  ) : (
    content
  );
}
