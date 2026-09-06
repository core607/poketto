import { articles } from "../../lib/public-api";
import { ArticleList } from "../../components/articles";
import { pageOffset } from "../../lib/pagination";
export const metadata = { title: "搜索" };
export default async function Search({
  searchParams,
}: {
  searchParams: Promise<{
    query?: string | string[];
    offset?: string | string[];
  }>;
}) {
  const { query: rawQuery, offset = "0" } = await searchParams;
  const query = String(rawQuery ?? "");
  const tooLong = query.length > 200;
  const page =
    query && !tooLong
      ? await articles({ query, offset: pageOffset(offset), limit: "12" })
      : null;
  return (
    <div className="page-shell">
      <header className="page-heading">
        <p className="eyebrow">想找的，也许就在这里</p>
        <h1>搜索记录</h1>
      </header>
      <form action="/search" className="search-form">
        <label htmlFor="query" className="sr-only">
          搜索公开文章
        </label>
        <input
          id="query"
          name="query"
          type="search"
          defaultValue={query}
          aria-invalid={tooLong || undefined}
          aria-describedby={tooLong ? "search-validation" : undefined}
          maxLength={200}
          placeholder="输入标题或正文中的文字…"
          required
        />
        <button>
          搜索 <span aria-hidden>↗</span>
        </button>
      </form>
      {tooLong && (
        <p id="search-validation" className="notice danger" role="alert">
          搜索内容过长，请缩短后再试。
        </p>
      )}
      <p className="muted search-help">在公开文章中按原文匹配。</p>
      {page && (
        <ArticleList page={page} base="/search" parameters={{ query }} />
      )}
    </div>
  );
}
