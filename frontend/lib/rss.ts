import { articleHref, xml } from "./format";
import { PublicApiError, publicOrigin } from "./public-api";
import type { ArticleSummary } from "./types";

type Feed = {
  title: string;
  /** The channel's path under the public origin. */
  link: string;
  description: string;
  /** The space whose articles are listed; absent for the root feed. */
  space?: string;
  items: ArticleSummary[];
};

/**
 * Answers an RSS 2.0 feed of the loaded articles. Any failure answers 503 instead of a stale or
 * partial feed; when notFound is given, a missing source answers 404 with that text.
 */
export async function rss(
  load: () => Promise<Feed>,
  notFound?: string,
): Promise<Response> {
  try {
    const origin = publicOrigin();
    const feed = await load();
    const items = feed.items
      .map((item) => {
        const link = xml(origin + articleHref(item.route, feed.space));
        return `<item><title>${xml(item.title)}</title><link>${link}</link><guid>${link}</guid><pubDate>${new Date(item.createdAt).toUTCString()}</pubDate><description>${xml(item.snippet)}</description>${item.tags.map((tag) => `<category>${xml(tag)}</category>`).join("")}</item>`;
      })
      .join("");
    return new Response(
      `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>${xml(feed.title)}</title><link>${xml(origin + feed.link)}</link><description>${xml(feed.description)}</description><language>zh-CN</language>${items}</channel></rss>`,
      {
        headers: {
          "Content-Type": "application/rss+xml; charset=utf-8",
          "Cache-Control": "no-store",
        },
      },
    );
  } catch (error) {
    const missing =
      notFound !== undefined &&
      error instanceof PublicApiError &&
      error.status === 404;
    return new Response(missing ? notFound : "订阅暂时不可用。", {
      status: missing ? 404 : 503,
      headers: { "Cache-Control": "no-store" },
    });
  }
}
