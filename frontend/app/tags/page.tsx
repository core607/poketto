import { articles, tags } from "../../lib/public-api";
import { ArticleList } from "../../components/articles";
import { pageOffset } from "../../lib/pagination";
import { notFound } from "next/navigation";

export const metadata = { title: "标签" };
export default async function Tags({
  searchParams,
}: {
  searchParams: Promise<{
    tag?: string | string[];
    offset?: string | string[];
    tagOffset?: string | string[];
  }>;
}) {
  const { tag: rawTag, offset = "0", tagOffset = "0" } = await searchParams;
  const tag = String(rawTag ?? "");
  if (tag.length > 64) notFound();
  if (tag) {
    const page = await articles({
      tag,
      offset: pageOffset(offset),
      limit: "12",
    });
    return (
      <div className="page-shell">
        <a className="back-link" href="/tags">
          ← 所有标签
        </a>
        <header className="page-heading">
          <p className="eyebrow">循着一条线索</p>
          <h1>#{tag}</h1>
        </header>
        <ArticleList page={page} base="/tags" parameters={{ tag }} />
      </div>
    );
  }
  const page = await tags({
    offset: pageOffset(tagOffset, 320000),
    limit: "100",
  });
  return (
    <div className="page-shell">
      <header className="page-heading">
        <p className="eyebrow">把相近的想法连起来</p>
        <h1>标签</h1>
        <p>挑一个感兴趣的话题，接着往下读。</p>
      </header>
      <div className="tag-cloud">
        {page.tags.map((item) => (
          <a href={"/tags?tag=" + encodeURIComponent(item)} key={item}>
            #{item} <span>↗</span>
          </a>
        ))}
        {!page.tags.length && <p className="muted">公开文章还没有标签。</p>}
      </div>
      <nav className="pagination" aria-label="标签翻页">
        {page.offset > 0 ? (
          <a href={"/tags?tagOffset=" + Math.max(0, page.offset - page.limit)}>
            ← 上一页
          </a>
        ) : (
          <span />
        )}
        <span>共 {page.total} 个标签</span>
        {page.offset + page.limit < page.total ? (
          <a href={"/tags?tagOffset=" + (page.offset + page.limit)}>下一页 →</a>
        ) : (
          <span />
        )}
      </nav>
    </div>
  );
}
