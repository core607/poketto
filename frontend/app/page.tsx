import { redirect } from "next/navigation";
import { discovery, PublicApiError } from "../lib/public-api";
import type { DiscoveryPage } from "../lib/types";
import { articleHref, date, spaceHref } from "../lib/format";
import { DiscoveryCover } from "../components/discovery-cover";
import { HomeNavigation } from "../components/home-navigation";
import { CommunityDashboard } from "../components/community-dashboard";
import { Avatar, Icon } from "../components/ui/icons";
import { Pager } from "../components/ui/pager";

type Parameters = {
  batch?: string | string[];
  afterBatch?: string | string[];
  offset?: string | string[];
  tag?: string | string[];
  view?: string | string[];
};
export default async function Home({
  searchParams,
}: {
  searchParams: Promise<Parameters>;
}) {
  const parameters = await searchParams;
  if (parameters.view === "following")
    return (
      <div className="page">
        <DiscoverHead />
        <div className="discover-bar">
          <HomeNavigation following />
        </div>
        <CommunityDashboard embedded />
      </div>
    );
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
    ...(typeof parameters.tag === "string" ? { tag: parameters.tag } : {}),
  }).catch((error) => {
    if (error instanceof PublicApiError && [400, 410].includes(error.status))
      return error.status;
    throw error;
  });
  if (typeof page === "number")
    return (
      <div className="state-page">
        <h1>{page === 400 ? "换个方式继续发现。" : "这一批已经翻完了。"}</h1>
        <p>
          {page === 400
            ? "标签或翻页参数无效。标签最多 64 个字符；更换标签时，请开始新一批。"
            : "浏览记录已过期，重新开始一批就好。"}
        </p>
        {page === 400 && (
          <DiscoveryTagForm
            tag={typeof parameters.tag === "string" ? parameters.tag : ""}
          />
        )}
        <a className="btn btn-primary" href="/">
          <Icon name="shuffle" />
          开始新一批
        </a>
      </div>
    );
  if (!batch) redirect("/?" + new URLSearchParams({ batch: page.batch }));
  const pageHref = (position: number) =>
    "/?" + new URLSearchParams({ batch: page.batch, offset: String(position) });
  const another = "/?" + new URLSearchParams({ afterBatch: page.batch });
  return (
    <div className="page">
      <DiscoverHead />
      <div className="discover-bar">
        <HomeNavigation />
        <a className="btn btn-ghost" href={another}>
          <Icon name="shuffle" />
          换一批
        </a>
      </div>
      <DiscoverTags page={page} />
      <div className="card-grid">
        {page.items.map((item) => (
          <DiscoveryCard key={item.space + ":" + item.route} item={item} />
        ))}
      </div>
      {!page.items.length && (
        <div className="empty">
          <span className="empty-mark">
            <Icon name="sparkle" />
          </span>
          <h2>这一页暂时安静。</h2>
          <p>内容可能还在同步，或已不再公开。换一批试试。</p>
        </div>
      )}
      <Pager
        label="发现翻页"
        previous={
          page.previousOffset !== null ? pageHref(page.previousOffset) : null
        }
        next={page.nextOffset !== null ? pageHref(page.nextOffset) : null}
        status={`第 ${Math.floor(page.offset / page.limit) + 1} 页`}
        end={
          page.items.length > 0 && (
            <a className="btn btn-secondary" href={another}>
              <Icon name="shuffle" />
              换一批
            </a>
          )
        }
      />
    </div>
  );
}

function DiscoverHead() {
  return (
    <header className="discover-head">
      <div>
        <p className="eyebrow">来自不同空间的记录</p>
        <h1>
          今天，发现一点新东西<em>。</em>
        </h1>
        <p>随意翻翻，也许会遇见想要留下的一页。</p>
      </div>
      <form action="/search" className="search-box" role="search">
        <Icon name="search" />
        <label htmlFor="discover-query" className="sr-only">
          搜索所有公开空间
        </label>
        <input
          id="discover-query"
          className="input"
          name="query"
          type="search"
          maxLength={200}
          required
          placeholder="搜索所有公开空间"
        />
        <button className="btn btn-primary">搜索</button>
      </form>
    </header>
  );
}

function DiscoverTags({ page }: { page: DiscoveryPage }) {
  const tags = page.tag
    ? []
    : [...new Set(page.items.flatMap((item) => item.tags))].slice(0, 10);
  return (
    <div className="discover-tags">
      <div className="chip-row">
        {page.tag ? (
          <>
            <a className="chip" href="/">
              全部
            </a>
            <span className="chip" aria-current="true">
              #{page.tag}
            </span>
          </>
        ) : (
          tags.map((tag) => (
            <a
              className="chip"
              key={tag}
              href={"/?tag=" + encodeURIComponent(tag)}
            >
              #{tag}
            </a>
          ))
        )}
        <DiscoveryTagForm tag={page.tag} />
      </div>
      {page.tag && (
        <p className="muted discover-note">正在发现「{page.tag}」相关内容。</p>
      )}
    </div>
  );
}

function DiscoveryTagForm({ tag }: { tag: string }) {
  return (
    <form className="tag-form" action="/" role="search">
      <label htmlFor="discovery-tag" className="sr-only">
        按标签发现
      </label>
      <Icon name="tag" />
      <input
        id="discovery-tag"
        name="tag"
        defaultValue={tag}
        maxLength={128}
        pattern=".{0,64}"
        title="标签最多 64 个字符"
        placeholder="按标签发现"
      />
    </form>
  );
}

function DiscoveryCard({ item }: { item: DiscoveryPage["items"][number] }) {
  const href = articleHref(item.route, item.space);
  const kind =
    item.album || item.collection
      ? [item.album && "相册", item.collection && "合集"]
          .filter(Boolean)
          .join(" · ")
      : item.folderPage
        ? "目录"
        : null;
  return (
    <article className="card">
      {item.album && (
        <DiscoveryCover src={item.cover} href={href} title={item.title} />
      )}
      <div className="card-meta">
        <a className="space-link" href={spaceHref(item.space)}>
          <Avatar name={item.spaceName} />
          {item.spaceName}
        </a>
        {item.authorName !== item.spaceName && (
          <span className="author-name">{item.authorName}</span>
        )}
        {kind && <span className="kind">{kind}</span>}
        <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
      </div>
      <h2 className="card-title">
        <a href={href}>{item.title}</a>
      </h2>
      {item.snippet && <p className="card-snippet">{item.snippet}</p>}
      {item.tags.length > 0 && (
        <div className="card-tags">
          {item.tags.slice(0, 4).map((tag) => (
            <a
              className="tag"
              href={"/?tag=" + encodeURIComponent(tag)}
              key={tag}
            >
              #{tag}
            </a>
          ))}
        </div>
      )}
    </article>
  );
}
