import { articles } from "../../lib/public-api";
import { articleHref, date } from "../../lib/format";
import { pageOffset } from "../../lib/pagination";
export const metadata = { title: "归档" };
export default async function Archive({
  searchParams,
}: {
  searchParams: Promise<{ offset?: string | string[] }>;
}) {
  const { offset = "0" } = await searchParams;
  const page = await articles({ offset: pageOffset(offset), limit: "100" });
  const years = Map.groupBy(page.items, (item) => item.createdAt.slice(0, 4));
  return (
    <div className="page-shell narrow">
      <header className="page-heading">
        <p className="eyebrow">时间留下的脚印</p>
        <h1>归档</h1>
        <p>{page.total} 篇记录，按时间慢慢翻。</p>
      </header>
      {Array.from(years, ([year, items]) => (
        <section className="archive-year" key={year}>
          <h2>{year}</h2>
          <div>
            {items.map((item) => (
              <a
                className="archive-entry"
                href={articleHref(item.route)}
                key={item.route}
              >
                <time dateTime={item.createdAt}>{date(item.createdAt)}</time>
                <span>{item.title}</span>
                <span aria-hidden>↗</span>
              </a>
            ))}
          </div>
        </section>
      ))}
      {!page.total && <p className="muted">第一篇记录还在路上。</p>}
      <nav className="pagination" aria-label="归档翻页">
        {page.offset > 0 ? (
          <a href={"/archive?offset=" + Math.max(0, page.offset - page.limit)}>
            ← 上一页
          </a>
        ) : (
          <span />
        )}
        {page.offset + page.limit < page.total && (
          <a href={"/archive?offset=" + (page.offset + page.limit)}>下一页 →</a>
        )}
      </nav>
    </div>
  );
}
