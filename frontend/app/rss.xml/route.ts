import { articles, publicOrigin } from "../../lib/public-api";
import { articleHref, xml } from "../../lib/format";
export const dynamic = "force-dynamic";
export async function GET() {
  try {
    const origin = publicOrigin();
    const page = await articles({ limit: "30" });
    const items = page.items
      .map(
        (item) =>
          `<item><title>${xml(item.title)}</title><link>${xml(origin + articleHref(item.route))}</link><guid>${xml(origin + articleHref(item.route))}</guid><pubDate>${new Date(item.createdAt).toUTCString()}</pubDate><description>${xml(item.snippet)}</description>${item.tags.map((tag) => `<category>${xml(tag)}</category>`).join("")}</item>`,
      )
      .join("");
    return new Response(
      `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Poketto · 记录与收藏</title><link>${xml(origin)}</link><description>那些值得留下的想法、故事与发现。</description><language>zh-CN</language>${items}</channel></rss>`,
      {
        headers: {
          "Content-Type": "application/rss+xml; charset=utf-8",
          "Cache-Control": "no-store",
        },
      },
    );
  } catch {
    return new Response("订阅暂时不可用。", {
      status: 503,
      headers: { "Cache-Control": "no-store" },
    });
  }
}
