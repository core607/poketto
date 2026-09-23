import type { Metadata } from "next";
import { notFound } from "next/navigation";
import {
  spaceArticle,
  spaceInfo,
  PublicApiError,
} from "../../../../../lib/public-api";
import {
  articleHref,
  coverHref,
  date,
  routeFromSegments,
  spaceFeed,
  spaceHref,
} from "../../../../../lib/format";
import { plainSummary } from "../../../../../lib/summary";
import { JsonLd, absoluteUrl } from "../../../../../components/json-ld";
import { Markdown } from "../../../../../components/markdown";
import { Gallery } from "../../../../../components/gallery";
import { ArticleCommunity } from "../../../../../components/article-community";
import {
  CollectionPanel,
  hasCollectionPanel,
  selectedMembership,
  SequenceNavigation,
} from "../../../../../components/collection-navigation";
import { Avatar, Icon } from "../../../../../components/ui/icons";
import {
  readingSearchReturn,
  type ReadingSearchParameters,
} from "../../../../../lib/search-return";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ space: string; slug?: string[] }>;
}): Promise<Metadata> {
  const { space, slug = [] } = await params;
  const route = "/" + slug.join("/");
  try {
    const [value, info] = await Promise.all([
      spaceArticle(space, route),
      spaceInfo(space).catch(() => null),
    ]);
    const name = info?.displayName ?? space;
    const description = plainSummary(value.body, value.title) || undefined;
    const url = articleHref(route, space);
    // The cover address falls back to the site's share image when the article has none.
    const image = coverHref(route, space);
    return {
      title: value.title,
      description,
      alternates: { canonical: url, types: spaceFeed(space, name) },
      openGraph: {
        type: "article",
        siteName: name,
        title: value.title,
        description,
        url,
        publishedTime: value.createdAt,
        modifiedTime: value.updatedAt,
        authors: [value.authorName],
        images: [{ url: image, alt: value.title }],
      },
      twitter: {
        card: "summary_large_image",
        title: value.title,
        description,
        images: [image],
      },
    };
  } catch {
    return { title: "文章" };
  }
}

export default async function Article({
  params,
  searchParams,
}: {
  params: Promise<{ space: string; slug?: string[] }>;
  searchParams?: Promise<
    ReadingSearchParameters & { collection?: string | string[] }
  >;
}) {
  const { space, slug = [] } = await params;
  // Next's page catch-all segments are URI-encoded; metadata params are decoded.
  const route = routeFromSegments(slug);
  if (route === null) notFound();
  const value = await spaceArticle(space, route).catch((error) => {
    if (error instanceof PublicApiError && error.status === 404) notFound();
    throw error;
  });
  // The space name only labels the breadcrumb; the article stays readable without it.
  const spaceName = await spaceInfo(space)
    .then((info) => info.displayName || space)
    .catch(() => space);
  const parameters = (await searchParams) ?? {};
  const selected =
    typeof parameters.collection === "string"
      ? parameters.collection
      : undefined;
  const returnToSearch = readingSearchReturn(parameters, space);
  const folders = route.split("/").slice(1, -1);
  const collection = (
    <CollectionPanel
      navigation={value.navigation}
      selected={selected}
      route={value.route}
      space={space}
    />
  );
  return (
    <div
      className={
        hasCollectionPanel(value.navigation)
          ? "read-layout has-rail"
          : "read-layout"
      }
    >
      <article className="reading-shell">
        <JsonLd
          data={{
            "@context": "https://schema.org",
            "@type": "BlogPosting",
            headline: value.title,
            description: plainSummary(value.body, value.title) || undefined,
            datePublished: value.createdAt,
            dateModified: value.updatedAt,
            author: { "@type": "Person", name: value.authorName },
            publisher: { "@type": "Organization", name: spaceName },
            image: absoluteUrl(coverHref(route, space)),
            mainEntityOfPage: absoluteUrl(articleHref(route, space)),
            keywords: value.tags.length ? value.tags.join(", ") : undefined,
            inLanguage: "zh-CN",
          }}
        />
        {returnToSearch && (
          <a href={returnToSearch} className="back-link">
            <Icon name="arrowLeft" />
            返回搜索结果
          </a>
        )}
        <nav className="crumbs" aria-label="所在位置">
          <a href={spaceHref(space)}>
            <Avatar name={spaceName} />
            {spaceName}
          </a>
          {folders.map((folder, index) => (
            <span key={index}>
              <span className="sep">/</span> {folder}
            </span>
          ))}
        </nav>
        <header>
          <h1 className="read-title">{value.title}</h1>
          <div className="read-meta">
            {value.authorName !== spaceName && (
              <span className="author-name" title="作者自行填写的署名">
                {value.authorName}
              </span>
            )}
            <time dateTime={value.createdAt}>{date(value.createdAt)}</time>
            {value.folderPage && <span className="kind">目录</span>}
            {value.tags.length > 0 && (
              <span className="card-tags">
                {value.tags.map((tag) => (
                  <a
                    className="tag"
                    href={
                      spaceHref(space) + "/tags?tag=" + encodeURIComponent(tag)
                    }
                    key={tag}
                  >
                    #{tag}
                  </a>
                ))}
              </span>
            )}
          </div>
        </header>
        {hasCollectionPanel(value.navigation) && (
          <div className="read-inline-collection">{collection}</div>
        )}
        <div className="read-body">
          <Markdown
            space={space}
            source={value.body}
            pageTitle={value.title}
            images={value.images}
            links={value.links}
            downloads={value.downloads}
            playback={value.playback}
            collection={
              value.navigation && {
                route: value.route,
                entries: value.navigation.entries,
              }
            }
          />
          <Gallery items={value.gallery} status={value.galleryStatus} />
        </div>
        <SequenceNavigation
          membership={selectedMembership(value.navigation, selected)}
          space={space}
        />
        <footer className="read-end">
          <span>最后更新于 {date(value.updatedAt)}</span>
          <a href={spaceHref(space)}>更多来自「{spaceName}」的记录 →</a>
        </footer>
        <ArticleCommunity space={space} articleId={value.articleId} />
      </article>
      {hasCollectionPanel(value.navigation) && (
        <aside className="read-rail" aria-label="合集">
          {collection}
        </aside>
      )}
    </div>
  );
}
