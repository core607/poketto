import { notFound } from "next/navigation";
import {
  PublicApiError,
  spaceInfo,
  spaceArticle,
  spaceArticles,
  spaceTags,
  type PublicSpace,
} from "../lib/public-api";
import { spaceHref } from "../lib/format";
import { pageOffset } from "../lib/pagination";
import { ArticleList } from "./articles";
import { Markdown } from "./markdown";
import { Gallery } from "./gallery";
import { SpaceFollow } from "./article-community";

export async function requirePublicSpace(slug: string) {
  return spaceInfo(slug).catch((error) => {
    if (error instanceof PublicApiError && error.status === 404) notFound();
    throw error;
  });
}

export function SpaceNavigation({ space }: { space: PublicSpace }) {
  const base = spaceHref(space.slug);
  return (
    <header className="page-heading">
      <p className="eyebrow">公开空间</p>
      <h1>{space.displayName}</h1>
      <nav className="tag-row" aria-label="空间导航">
        <a href={base}>空间首页</a>
        <a href={base + "/search"}>搜索</a>
        <a href={base + "/tags"}>标签</a>
        <a href={base + "/archive"}>归档</a>
      </nav>
      <SpaceFollow space={space.slug} />
    </header>
  );
}

export type SpaceParameters = {
  query?: string | string[];
  tag?: string | string[];
  offset?: string | string[];
};

export async function PublicSpacePage({
  slug,
  view,
  parameters,
}: {
  slug: string;
  view: "home" | "search" | "tags" | "archive";
  parameters: SpaceParameters;
}) {
  const space = await requirePublicSpace(slug);
  const base = spaceHref(space.slug);
  const query = String(parameters.query ?? "");
  const tag = String(parameters.tag ?? "");
  const invalid = query.length > 200 || [...tag].length > 64;
  const tagListing = view === "tags" && !tag;
  const page =
    !invalid && !tagListing && (view !== "search" || query)
      ? await spaceArticles(slug, {
          query: view === "search" ? query : "",
          tag: view === "tags" ? tag : "",
          offset: pageOffset(parameters.offset),
          limit: "12",
        })
      : null;
  const tags = tagListing
    ? await spaceTags(slug, {
        offset: pageOffset(parameters.offset, 320000),
        limit: "100",
      })
    : null;
  const root =
    view === "home"
      ? await spaceArticle(slug, "/").catch((error) => {
          if (error instanceof PublicApiError && error.status === 404)
            return null;
          throw error;
        })
      : null;
  if (root && page && root.commit !== page.commit)
    throw new PublicApiError(503);
  const listPath = base + (view === "home" ? "" : `/${view}`);
  return (
    <div className="page-shell">
      <SpaceNavigation space={space} />
      {view === "search" && (
        <form action={base + "/search"} className="search-form">
          <label htmlFor="query" className="sr-only">
            搜索这个空间
          </label>
          <input
            id="query"
            name="query"
            type="search"
            defaultValue={query}
            maxLength={200}
            required
            placeholder="搜索这个空间的公开文字…"
          />
          <button>搜索 ↗</button>
        </form>
      )}
      {invalid && (
        <p role="alert" className="notice danger">
          搜索内容或标签过长，请缩短后再试。
        </p>
      )}
      {root && (
        <section className="root-note">
          <p className="article-meta author-name">{root.authorName}</p>
          <Markdown
            source={root.body}
            images={root.images}
            links={root.links}
            downloads={root.downloads}
            space={slug}
            collection={
              root.navigation && {
                route: root.route,
                entries: root.navigation.entries,
              }
            }
          />
          <Gallery items={root.gallery} status={root.galleryStatus} />
        </section>
      )}
      {tags && (
        <>
          <h2>标签</h2>
          <div className="tag-cloud">
            {tags.tags.map((value) => (
              <a
                key={value}
                href={base + "/tags?tag=" + encodeURIComponent(value)}
              >
                #{value}
              </a>
            ))}
          </div>
          {!tags.tags.length && <p>这个空间还没有公开标签。</p>}
          <nav className="pagination" aria-label="标签翻页">
            {tags.offset > 0 ? (
              <a
                href={
                  base + "/tags?offset=" + Math.max(0, tags.offset - tags.limit)
                }
              >
                ← 上一页
              </a>
            ) : (
              <span />
            )}
            <span>共 {tags.total} 个标签</span>
            {tags.offset + tags.limit < tags.total && (
              <a href={base + "/tags?offset=" + (tags.offset + tags.limit)}>
                下一页 →
              </a>
            )}
          </nav>
        </>
      )}
      {page && (
        <>
          <h2>
            {view === "search"
              ? "搜索结果"
              : view === "archive"
                ? "归档"
                : tag
                  ? `#${tag}`
                  : "公开记录"}
          </h2>
          <ArticleList
            page={page}
            space={slug}
            base={listPath}
            parameters={view === "search" ? { query } : tag ? { tag } : {}}
            searchQuery={view === "search" ? query : undefined}
          />
        </>
      )}
    </div>
  );
}
