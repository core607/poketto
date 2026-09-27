import { spaceArticles, spaceInfo } from "../../../../lib/public-api";
import { spaceHref } from "../../../../lib/format";
import { rss } from "../../../../lib/rss";

export const dynamic = "force-dynamic";

/** The latest public records of one space as RSS 2.0. */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ space: string }> },
) {
  const { space } = await params;
  return rss(async () => {
    const [info, page] = await Promise.all([
      spaceInfo(space),
      spaceArticles(space, { limit: "30" }),
    ]);
    return {
      title: `${info.displayName} · Poketto`,
      link: spaceHref(info.slug),
      description: `「${info.displayName}」的公开记录`,
      space: info.slug,
      items: page.items,
    };
  }, "没有这个空间。");
}
