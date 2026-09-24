import { siteSearch } from "../../lib/public-api";
import { ArticleList } from "../../components/articles";
import { Icon } from "../../components/ui/icons";
import { pageOffset } from "../../lib/pagination";
// Result pages are endless and thin; their links are still followed.
export const metadata = {
  title: "搜索",
  robots: { index: false, follow: true },
};
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
      ? await siteSearch({ query, offset: pageOffset(offset), limit: "12" })
      : null;
  return (
    <div className="page page-narrow">
      <header className="page-head">
        <p className="eyebrow">想找的，也许就在这里</p>
        <h1>搜索</h1>
        <p>在所有已开启网站的空间中，按原文匹配标题和正文。</p>
      </header>
      <form action="/search" className="search-box" role="search">
        <Icon name="search" />
        <label htmlFor="query" className="sr-only">
          搜索公开文章
        </label>
        <input
          id="query"
          className="input"
          name="query"
          type="search"
          defaultValue={query}
          aria-invalid={tooLong || undefined}
          aria-describedby={tooLong ? "search-validation" : undefined}
          maxLength={200}
          placeholder="输入标题或正文中的文字…"
          required
          autoFocus={!query}
        />
        <button className="btn btn-primary">搜索</button>
      </form>
      {tooLong && (
        <p
          id="search-validation"
          className="notice danger"
          role="alert"
          style={{ marginTop: 16 }}
        >
          搜索内容过长，请缩短后再试。
        </p>
      )}
      {page && (
        <>
          <div className="section-head">
            <h2>「{query}」</h2>
            <span>找到 {page.total} 篇</span>
          </div>
          <ArticleList
            page={{
              ...page,
              items: page.items.map(({ space, spaceName, document }) => ({
                ...document,
                space,
                spaceName,
              })),
            }}
            base="/search"
            parameters={{ query }}
            searchQuery={query}
            empty="没有找到匹配的公开文字，换个说法试试。"
          />
        </>
      )}
    </div>
  );
}
