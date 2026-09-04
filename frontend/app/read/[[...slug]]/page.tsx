import { notFound } from "next/navigation";
import { article, PublicApiError } from "../../../lib/public-api";
import { date } from "../../../lib/format";
import { Markdown } from "../../../components/markdown";
import { Gallery } from "../../../components/gallery";

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug?: string[] }>;
}) {
  const { slug = [] } = await params;
  try {
    const value = await article("/" + slug.join("/"));
    return { title: value.title };
  } catch {
    return { title: "文章" };
  }
}

export default async function Article({
  params,
}: {
  params: Promise<{ slug?: string[] }>;
}) {
  const { slug = [] } = await params;
  const route = "/" + slug.join("/");
  const value = await article(route).catch((error) => {
    if (error instanceof PublicApiError && error.status === 404) notFound();
    throw error;
  });
  return (
    <article className="reading-shell">
      <a href="/" className="back-link">
        ← 回到文章
      </a>
      <header className="reading-header">
        <div className="article-meta">
          <time dateTime={value.createdAt}>{date(value.createdAt)}</time>
          {value.folderPage && <span>文件夹笔记</span>}
        </div>
        <h1>{value.title}</h1>
        <div className="tag-row">
          {value.tags.map((tag) => (
            <a href={"/tags?tag=" + encodeURIComponent(tag)} key={tag}>
              {tag}
            </a>
          ))}
        </div>
      </header>
      <Markdown source={value.body} images={value.images} links={value.links} />
      <Gallery items={value.gallery} />
      <footer className="article-footer">
        最后更新于 {date(value.updatedAt)}
        <a href="/">更多记录 ↗</a>
      </footer>
    </article>
  );
}
