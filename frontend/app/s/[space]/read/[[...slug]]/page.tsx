import { notFound } from "next/navigation";
import { spaceArticle, PublicApiError } from "../../../../../lib/public-api";
import { date, spaceHref } from "../../../../../lib/format";
import { Markdown } from "../../../../../components/markdown";
import { Gallery } from "../../../../../components/gallery";
import { CollectionNavigation } from "../../../../../components/collection-navigation";
import {
  readingSearchReturn,
  type ReadingSearchParameters,
} from "../../../../../lib/search-return";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ space: string; slug?: string[] }>;
}) {
  const { space, slug = [] } = await params;
  try {
    const value = await spaceArticle(space, "/" + slug.join("/"));
    return { title: value.title };
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
  let segments: string[];
  try {
    segments = slug.map(decodeURIComponent);
  } catch {
    notFound();
  }
  if (
    segments.some(
      (segment) =>
        !segment ||
        segment === "." ||
        segment === ".." ||
        /[/\\\u0000-\u001f\u007f]/.test(segment),
    )
  )
    notFound();
  const route = "/" + segments.join("/");
  const value = await spaceArticle(space, route).catch((error) => {
    if (error instanceof PublicApiError && error.status === 404) notFound();
    throw error;
  });
  const parameters = (await searchParams) ?? {};
  const selected = parameters.collection;
  const returnToSearch = readingSearchReturn(parameters, space);
  return (
    <article className="reading-shell">
      <a href={returnToSearch ?? spaceHref(space)} className="back-link">
        {returnToSearch ? "← 返回搜索结果" : "← 回到这个空间"}
      </a>
      <header className="reading-header">
        <div className="article-meta">
          <span className="author-name">{value.authorName}</span>
          <time dateTime={value.createdAt}>{date(value.createdAt)}</time>
          {value.folderPage && <span>文件夹笔记</span>}
        </div>
        <h1>{value.title}</h1>
        <div className="tag-row">
          {value.tags.map((tag) => (
            <a
              href={spaceHref(space) + "/tags?tag=" + encodeURIComponent(tag)}
              key={tag}
            >
              {tag}
            </a>
          ))}
        </div>
      </header>
      <CollectionNavigation
        navigation={value.navigation}
        selected={typeof selected === "string" ? selected : undefined}
        route={value.route}
        space={space}
      />
      <Markdown
        space={space}
        source={value.body}
        pageTitle={value.title}
        images={value.images}
        links={value.links}
        downloads={value.downloads}
        collection={
          value.navigation && {
            route: value.route,
            entries: value.navigation.entries,
          }
        }
      />
      <Gallery items={value.gallery} status={value.galleryStatus} />
      <footer className="article-footer">
        最后更新于 {date(value.updatedAt)}
        <a href={spaceHref(space)}>更多记录 ↗</a>
      </footer>
    </article>
  );
}
