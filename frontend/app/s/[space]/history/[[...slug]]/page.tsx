import type { Metadata } from "next";
import { notFound } from "next/navigation";
import {
  spaceHistory,
  PublicApiError,
  type RevisionHistory,
} from "../../../../../lib/public-api";
import {
  articleHref,
  dateTime,
  routeFromSegments,
} from "../../../../../lib/format";
import { RevisionComparison } from "../../../../../components/revision-comparison";
import { Icon } from "../../../../../components/ui/icons";

// Earlier versions are for readers checking a correction, not for search results.
export const metadata: Metadata = {
  title: "修订历史",
  robots: { index: false, follow: true },
};

export default async function History({
  params,
}: {
  params: Promise<{ space: string; slug?: string[] }>;
}) {
  const { space, slug = [] } = await params;
  const route = routeFromSegments(slug);
  if (route === null) notFound();
  let history: RevisionHistory | null = null;
  try {
    history = await spaceHistory(space, route);
  } catch (error) {
    if (error instanceof PublicApiError && error.status === 404) notFound();
  }
  return (
    <article className="reading-shell revision-history">
      <a href={articleHref(route, space)} className="back-link">
        <Icon name="arrowLeft" />
        返回文章
      </a>
      {history === null ? (
        <>
          <h1 className="read-title">修订历史</h1>
          <p role="alert">修订历史暂时读不到，请稍后刷新重试。</p>
        </>
      ) : (
        <>
          <h1 className="read-title">{history.title}</h1>
          <p className="muted">
            {history.versions.length > 1
              ? `公开以来共 ${history.versions.length} 个版本。`
              : "公开以来正文还没有改动过。"}
            {!history.complete && "更早的版本超出了读取范围，没有列出。"}
            只列出正文，时间取自保存它的提交。
          </p>
          {history.versions.length > 1 ? (
            <RevisionComparison versions={history.versions} />
          ) : (
            history.versions.length === 1 && (
              <p>当前版本保存于 {dateTime(history.versions[0].savedAt)}。</p>
            )
          )}
        </>
      )}
    </article>
  );
}
