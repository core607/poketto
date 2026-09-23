import { notFound } from "next/navigation";
import {
  PublicApiError,
  spaceInfo,
  spaceArticle,
  spaceArticles,
  spaceTags,
  type PublicSpace,
} from "../lib/public-api";
import type { ArticlePage } from "../lib/types";
import { articleHref, date, spaceHref } from "../lib/format";
import { pageOffset } from "../lib/pagination";
import { ArticleList } from "./articles";
import { Markdown } from "./markdown";
import { Gallery } from "./gallery";
import { SpaceFollow } from "./article-community";
import { Avatar, Icon } from "./ui/icons";
import { Pager } from "./ui/pager";

export async function requirePublicSpace(slug: string) {
  return spaceInfo(slug).catch((error) => {
    if (error instanceof PublicApiError && error.status === 404) notFound();
    throw error;
  });
}

type View = "home" | "search" | "tags" | "archive";

function SpaceMast({
  space,
  view,
  total,
}: {
  space: PublicSpace;
  view: View;
  total?: number;
}) {
  const base = spaceHref(space.slug);
  const current = (target: View) => (view === target ? "page" : undefined);
  return (
    <header className="space-mast">
      <div className="space-mast-top">
        <Avatar name={space.displayName} large />
        <div className="space-mast-text">
          <h1>{space.displayName}</h1>
          {space.description && (
            <p className="space-description">{space.description}</p>
          )}
          <p className="space-sub">
            公开空间
            {total !== undefined && ` · ${total} 篇公开记录`}
          </p>
        </div>
        <div className="space-actions">
          <SpaceFollow space={space.slug} />
        </div>
      </div>
      <nav className="tabs" aria-label="空间导航">
        <a href={base} aria-current={current("home")}>
          首页
        </a>
        <a href={base + "/tags"} aria-current={current("tags")}>
          标签
        </a>
        <a href={base + "/archive"} aria-current={current("archive")}>
          归档
        </a>
        <a href={base + "/search"} aria-current={current("search")}>
          搜索
        </a>
      </nav>
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
  view: View;
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
          limit: view === "archive" ? "100" : "12",
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
    <div className="page">
      <SpaceMast
        space={space}
        view={view}
        total={view === "home" ? page?.total : undefined}
      />
      {invalid && (
        <p role="alert" className="notice danger" style={{ marginTop: 24 }}>
          搜索内容或标签过长，请缩短后再试。
        </p>
      )}
      {root && (
        <section className="space-intro" aria-label="空间介绍">
          <Markdown
            source={root.body}
            images={root.images}
            links={root.links}
            downloads={root.downloads}
            playback={root.playback}
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
      {view === "search" && (
        <form
          action={base + "/search"}
          className="search-box space-search"
          role="search"
        >
          <Icon name="search" />
          <label htmlFor="query" className="sr-only">
            搜索这个空间
          </label>
          <input
            id="query"
            className="input"
            name="query"
            type="search"
            defaultValue={query}
            maxLength={200}
            required
            placeholder={`在「${space.displayName}」中搜索`}
          />
          <button className="btn btn-primary">搜索</button>
        </form>
      )}
      {tags && (
        <>
          <div className="section-head">
            <h2>所有标签</h2>
            <span>共 {tags.total} 个</span>
          </div>
          {tags.tags.length ? (
            <div className="chip-row">
              {tags.tags.map((value) => (
                <a
                  className="chip"
                  key={value}
                  href={base + "/tags?tag=" + encodeURIComponent(value)}
                >
                  #{value}
                </a>
              ))}
            </div>
          ) : (
            <p className="muted">这个空间还没有公开标签。</p>
          )}
          <Pager
            label="标签翻页"
            previous={
              tags.offset > 0
                ? base + "/tags?offset=" + Math.max(0, tags.offset - tags.limit)
                : null
            }
            next={
              tags.offset + tags.limit < tags.total
                ? base + "/tags?offset=" + (tags.offset + tags.limit)
                : null
            }
          />
        </>
      )}
      {page && view === "archive" && (
        <Archive page={page} space={slug} base={listPath} />
      )}
      {page && view !== "archive" && (
        <>
          <div className="section-head">
            <h2>
              {view === "search"
                ? `「${query}」的搜索结果`
                : tag
                  ? `#${tag}`
                  : "全部记录"}
            </h2>
            {tag ? (
              <a className="link-btn" href={base + "/tags"}>
                所有标签
              </a>
            ) : (
              <span>共 {page.total} 篇</span>
            )}
          </div>
          <ArticleList
            page={page}
            space={slug}
            base={listPath}
            parameters={view === "search" ? { query } : tag ? { tag } : {}}
            searchQuery={view === "search" ? query : undefined}
            spaceName={space.displayName}
            empty={
              view === "search"
                ? "没有找到匹配的公开文字，换个说法试试。"
                : undefined
            }
          />
        </>
      )}
    </div>
  );
}

function Archive({
  page,
  space,
  base,
}: {
  page: ArticlePage;
  space: string;
  base: string;
}) {
  const years = Map.groupBy(page.items, (item) => item.createdAt.slice(0, 4));
  return (
    <>
      <div className="section-head">
        <h2>按时间翻阅</h2>
        <span>共 {page.total} 篇</span>
      </div>
      {Array.from(years, ([year, items]) => (
        <section className="archive-year" key={year}>
          <h2>{year}</h2>
          <div>
            {items.map((item) => (
              <a
                className="archive-row"
                href={articleHref(item.route, space)}
                key={item.route}
              >
                <time dateTime={item.createdAt}>
                  {date(item.createdAt).replace(/^\d+年/, "")}
                </time>
                <span>{item.title}</span>
              </a>
            ))}
          </div>
        </section>
      ))}
      {!page.total && <p className="muted">第一篇记录还在路上。</p>}
      <Pager
        label="归档翻页"
        previous={
          page.offset > 0
            ? base + "?offset=" + Math.max(0, page.offset - page.limit)
            : null
        }
        next={
          page.offset + page.limit < page.total
            ? base + "?offset=" + (page.offset + page.limit)
            : null
        }
      />
    </>
  );
}
