import {
  PublicApiError,
  publicOrigin,
  spaceArticles,
  spaceInfo,
} from "../../../../lib/public-api";
import { articleHref, spaceHref, xml } from "../../../../lib/format";

export const dynamic = "force-dynamic";

/** The latest public records of one space as RSS 2.0. */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ space: string }> },
) {
  const { space } = await params;
  try {
    const origin = publicOrigin();
    const [info, page] = await Promise.all([
      spaceInfo(space),
      spaceArticles(space, { limit: "30" }),
    ]);
    const items = page.items
      .map((item) => {
        const link = xml(origin + articleHref(item.route, info.slug));
        return `<item><title>${xml(item.title)}</title><link>${link}</link><guid>${link}</guid><pubDate>${new Date(item.createdAt).toUTCString()}</pubDate><description>${xml(item.snippet)}</description>${item.tags.map((tag) => `<category>${xml(tag)}</category>`).join("")}</item>`;
      })
      .join("");
    return new Response(
      `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>${xml(info.displayName)} · Poketto</title><link>${xml(origin + spaceHref(info.slug))}</link><description>${xml(`「${info.displayName}」的公开记录`)}</description><language>zh-CN</language>${items}</channel></rss>`,
      {
        headers: {
          "Content-Type": "application/rss+xml; charset=utf-8",
          "Cache-Control": "no-store",
        },
      },
    );
  } catch (error) {
    const missing = error instanceof PublicApiError && error.status === 404;
    return new Response(missing ? "没有这个空间。" : "订阅暂时不可用。", {
      status: missing ? 404 : 503,
      headers: { "Cache-Control": "no-store" },
    });
  }
}
