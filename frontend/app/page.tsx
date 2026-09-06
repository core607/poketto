import { article, articles, PublicApiError } from "../lib/public-api";
import { ArticleList } from "../components/articles";
import { Markdown } from "../components/markdown";
import { Gallery } from "../components/gallery";
import { pageOffset } from "../lib/pagination";

export default async function Home({
  searchParams,
}: {
  searchParams: Promise<{ offset?: string | string[] }>;
}) {
  const parameters = await searchParams;
  const page = await articles({
    offset: pageOffset(parameters.offset),
    limit: "12",
  });
  const root = await article("/").catch((error) => {
    if (error instanceof PublicApiError && error.status === 404) return null;
    throw error;
  });
  if (root && root.commit !== page.commit) throw new PublicApiError(503);
  return (
    <div className="page-shell">
      <section className="hero">
        <div>
          <p className="eyebrow">一个存放想法的小地方</p>
          <h1>
            {root ? (
              root.title
            ) : (
              <>
                把值得留下的，
                <br />
                放在这里<span className="accent">。</span>
              </>
            )}
          </h1>
          <p className="hero-description">
            一些记录，一些发现。慢慢积累，也随时回来翻翻。
          </p>
        </div>
        <div className="hero-aside">
          <span className="sunburst" aria-hidden>
            ✳
          </span>
          <p>
            随手记下。
            <br />
            认真收藏。
          </p>
          <span className="tiny-label">{page.total} 篇公开文章</span>
        </div>
      </section>
      {root && (
        <section className="root-note">
          <Markdown
            source={root.body}
            images={root.images}
            links={root.links}
          />
          <Gallery items={root.gallery} />
        </section>
      )}
      <div className="section-heading">
        <h2>最近的记录</h2>
        <a href="/archive">查看归档 ↗</a>
      </div>
      <ArticleList page={page} />
    </div>
  );
}
